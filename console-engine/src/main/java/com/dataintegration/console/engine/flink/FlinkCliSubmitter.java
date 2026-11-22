package com.dataintegration.console.engine.flink;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.engine.SubmitJobRequest;
import com.dataintegration.console.engine.SubmitJobRequest.RestoreMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Submits SeaTunnel jobs to a Flink Standalone or Session cluster via the local Flink CLI
 * ({@code flink run -d}).
 *
 * <p>Calling {@code bin/flink} directly avoids shell {@code eval} injection, skips the extra
 * {@code FlinkStarter} JVM and {@code runtime.tar.gz} packaging, and releases the client JVM as
 * soon as the job is accepted ({@code -d}). Rendered configs (which contain decrypted credentials)
 * are written to a mode-0600 temporary file and always deleted in {@code finally}.
 */
public class FlinkCliSubmitter {

    private static final Logger log = LoggerFactory.getLogger(FlinkCliSubmitter.class);

    private static final String SEATUNNEL_MAIN_CLASS = "org.apache.seatunnel.core.starter.flink.SeaTunnelFlink";
    private static final Pattern JOB_ID_PATTERN = Pattern.compile("JobID\\s+([0-9a-fA-F]{32})");
    private static final Set<PosixFilePermission> DIR_PERMS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMS = PosixFilePermissions.fromString("rw-------");

    private final FlinkClusterConfig config;
    private final Semaphore concurrency;

    public FlinkCliSubmitter(FlinkClusterConfig config) {
        this.config = config;
        this.concurrency = new Semaphore(config.maxConcurrentSubmits());
    }

    public String submit(SubmitJobRequest request) {
        validatePaths();
        Path tempConfig = null;
        boolean acquired = false;
        try {
            acquired = concurrency.tryAcquire(config.submitTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new ConsoleException(
                        ErrorCodes.ENGINE_UNAVAILABLE,
                        "timed out waiting for a local Flink CLI submit slot after " + config.submitTimeout());
            }
            tempConfig = writeTempConfig(request.config());
            List<String> command = buildCommand(request, tempConfig);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.put("SEATUNNEL_HOME", config.seatunnelHome());
            env.put("FLINK_HOME", config.flinkHome());
            if (config.javaHome() != null) {
                env.put("JAVA_HOME", config.javaHome());
            }

            Process process;
            try {
                process = pb.start();
            } catch (IOException e) {
                throw new ConsoleException(ErrorCodes.ENGINE_UNAVAILABLE, "cannot start flink CLI: " + e.getMessage(), e);
            }

            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readAllQuietly(process));
            boolean finished = process.waitFor(config.submitTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                String partial = awaitOutput(outputFuture);
                throw new ConsoleException(
                        ErrorCodes.ENGINE_RESPONSE_LOST,
                        "flink run timed out after " + config.submitTimeout() + "; output: " + clip(partial));
            }
            String output = awaitOutput(outputFuture);

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw classifyFailure(exitCode, output);
            }

            String jobId = extractJobId(output);
            if (jobId == null) {
                jobId = request.jobId();
            }
            if (jobId == null) {
                throw new ConsoleException(
                        ErrorCodes.ENGINE_RESPONSE_LOST, "flink run succeeded but returned no JobID: " + clip(output));
            }
            log.info("Submitted job '{}' to Flink {}, jobId={}", request.jobName(), config.restUrl(), jobId);
            return jobId;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConsoleException(ErrorCodes.ENGINE_RESPONSE_LOST, "interrupted while submitting Flink job", e);
        } finally {
            deleteQuietly(tempConfig);
            if (acquired) {
                concurrency.release();
            }
        }
    }

    /**
     * Classifies any non-zero {@code flink run} exit conservatively as
     * {@link ErrorCodes#ENGINE_RESPONSE_LOST} (CLAUDE.md): once the CLI process has started, we
     * cannot prove from unstructured stdout/stderr that the JobManager did not already accept the
     * job (e.g. a connection error after submission or from a connector). The caller looks the job
     * up by its pre-chosen {@code jobId} instead of retrying blindly.
     */
    static ConsoleException classifyFailure(int exitCode, String output) {
        return new ConsoleException(
                ErrorCodes.ENGINE_RESPONSE_LOST,
                "flink run exited with code " + exitCode + " (submission status unknown): " + clip(output));
    }

    List<String> buildCommand(SubmitJobRequest request, Path configFile) {
        if (request.restoreMode() != RestoreMode.NONE
                && (request.restorePath() == null || request.restorePath().isBlank())) {
            throw new ConsoleException(
                    ErrorCodes.INVALID_ARGUMENT,
                    "Flink " + request.restoreMode() + " restore requires a non-empty restorePath");
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(config.flinkHome(), "bin", "flink").toString());
        cmd.add("run");
        cmd.add("-d");
        cmd.add("-m");
        cmd.add(config.jobManagerAddress());
        if (request.jobId() != null && !request.jobId().isBlank()) {
            cmd.add("-D$internal.pipeline.job-id=" + request.jobId());
        }
        if (request.restoreMode() != RestoreMode.NONE) {
            cmd.add("-s");
            cmd.add(request.restorePath());
            cmd.add("-restoreMode");
            cmd.add("claim");
        }
        cmd.add("-c");
        cmd.add(SEATUNNEL_MAIN_CLASS);
        cmd.add(Path.of(config.seatunnelHome(), "starter", starterJarName(config.flinkVersion())).toString());
        cmd.add("--config");
        cmd.add(configFile.toString());
        if (request.jobName() != null && !request.jobName().isBlank()) {
            cmd.add("--name");
            cmd.add(request.jobName());
        }
        cmd.add("--deploy-mode");
        cmd.add("run");
        return cmd;
    }

    /** Maps a Flink version (e.g. {@code 1.20.1}) to the matching SeaTunnel starter jar. */
    static String starterJarName(String flinkVersion) {
        if (flinkVersion == null || flinkVersion.isBlank()) {
            return "seatunnel-flink-20-starter.jar";
        }
        String[] parts = flinkVersion.trim().split("\\.");
        if (parts.length >= 2) {
            try {
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1]);
                if (major == 1) {
                    if (minor == 20) {
                        return "seatunnel-flink-20-starter.jar";
                    }
                    if (minor == 19) {
                        throw new ConsoleException(
                                ErrorCodes.INVALID_ARGUMENT,
                                "Flink 1.19 is not supported by SeaTunnel 3.0; use Flink 1.20.x or 1.15-1.18");
                    }
                    if (minor >= 15 && minor <= 18) {
                        return "seatunnel-flink-15-starter.jar";
                    }
                    if (minor >= 12 && minor <= 14) {
                        return "seatunnel-flink-13-starter.jar";
                    }
                }
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return "seatunnel-flink-20-starter.jar";
    }

    static String extractJobId(String output) {
        if (output == null) {
            return null;
        }
        Matcher m = JOB_ID_PATTERN.matcher(output);
        return m.find() ? m.group(1) : null;
    }

    private void validatePaths() {
        if (config.flinkHome() == null) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "Flink 集群未配置本机 FLINK_HOME 路径");
        }
        if (config.seatunnelHome() == null) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "Flink 集群未配置本机 SeaTunnel 发行包路径");
        }
        Path flinkBin = Path.of(config.flinkHome(), "bin", "flink");
        if (!Files.isExecutable(flinkBin)) {
            throw new ConsoleException(ErrorCodes.ENGINE_UNAVAILABLE, "Flink 客户端不可执行：" + flinkBin);
        }
        Path starter = Path.of(config.seatunnelHome(), "starter", starterJarName(config.flinkVersion()));
        if (!Files.isRegularFile(starter)) {
            throw new ConsoleException(ErrorCodes.ENGINE_UNAVAILABLE, "找不到 SeaTunnel Flink starter jar：" + starter);
        }
    }

    private Path writeTempConfig(String hocon) {
        try {
            Path dir = config.tempDir() != null
                    ? config.tempDir()
                    : Path.of(System.getProperty("java.io.tmpdir"), "seatunnel-console-flink");
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
                setPosixPermsQuietly(dir, DIR_PERMS);
            }
            Path file = Files.createTempFile(dir, "job-", ".conf");
            setPosixPermsQuietly(file, FILE_PERMS);
            Files.writeString(file, hocon, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new ConsoleException(ErrorCodes.ENGINE_UNAVAILABLE, "cannot write temporary Flink config: " + e.getMessage(), e);
        }
    }

    private static void setPosixPermsQuietly(Path path, Set<PosixFilePermission> perms) {
        try {
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // non-POSIX filesystem
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete temporary Flink config {}: {}", path, e.getMessage());
        }
    }

    private static String readAllQuietly(Process process) {
        try {
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String awaitOutput(CompletableFuture<String> future) {
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (ExecutionException | TimeoutException e) {
            return "";
        }
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= 2000 ? trimmed : trimmed.substring(trimmed.length() - 2000);
    }
}
