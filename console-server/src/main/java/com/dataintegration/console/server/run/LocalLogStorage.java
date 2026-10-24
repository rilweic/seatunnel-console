package com.dataintegration.console.server.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Stores logs under {@code ${console.data-dir}/instance-logs/<yyyy-MM-dd>/<attemptId>.log} and
 * deletes day directories older than the retention period. With Docker the data dir must be a
 * mounted volume.
 */
@Component
public class LocalLogStorage implements LogStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalLogStorage.class);

    private static final String ROOT = "instance-logs";
    private static final Pattern KEY = Pattern.compile("instance-logs/\\d{4}-\\d{2}-\\d{2}/[0-9a-f-]{36}\\.log");

    private final Path dataDir;
    private final int retentionDays;

    public LocalLogStorage(
            @Value("${console.data-dir:./data}") String dataDir,
            @Value("${console.run.log-retention-days:30}") int retentionDays) {
        this.dataDir = Path.of(dataDir).toAbsolutePath().normalize();
        this.retentionDays = retentionDays;
    }

    @Override
    public String write(String attemptId, LocalDate day, String content) {
        String key = ROOT + "/" + day + "/" + attemptId + ".log";
        Path file = dataDir.resolve(key);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write log archive " + file, e);
        }
        return key;
    }

    @Override
    public Optional<String> read(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            return Optional.empty();
        }
        Path file = dataDir.resolve(key);
        try {
            return Files.exists(file) ? Optional.of(Files.readString(file, StandardCharsets.UTF_8)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read log archive " + file, e);
        }
    }

    @Override
    public void delete(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            return;
        }
        Path file = dataDir.resolve(key);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete log archive " + file, e);
        }
    }

    /** Daily at 03:30, drop whole day directories past retention. */
    @Scheduled(cron = "0 30 3 * * *")
    public void purgeExpired() {
        Path root = dataDir.resolve(ROOT);
        if (!Files.isDirectory(root)) {
            return;
        }
        LocalDate cutoff = LocalDate.now().minusDays(retentionDays);
        try (Stream<Path> days = Files.list(root)) {
            days.filter(Files::isDirectory)
                    .filter(dir -> isBefore(dir.getFileName().toString(), cutoff))
                    .forEach(LocalLogStorage::deleteRecursively);
        } catch (IOException e) {
            log.warn("Log retention sweep failed: {}", e.toString());
        }
    }

    private static boolean isBefore(String dirName, LocalDate cutoff) {
        try {
            return LocalDate.parse(dirName).isBefore(cutoff);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Cannot delete {}: {}", path, e.toString());
                }
            });
            log.info("Deleted expired log archives in {}", dir);
        } catch (IOException e) {
            log.warn("Cannot delete {}: {}", dir, e.toString());
        }
    }
}
