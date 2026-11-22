package com.dataintegration.console.engine.flink;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.engine.ConnectorOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads SeaTunnel connector and transform option definitions offline from the local distribution
 * via {@code bin/seatunnel-connector.sh -o <plugin> -pt <type>}, caching results per plugin.
 */
public class ConnectorOptionCliReader {

    private static final Logger log = LoggerFactory.getLogger(ConnectorOptionCliReader.class);

    private static final Pattern OPTION_LINE = Pattern.compile(
            "^\\s*Key:\\s*'([^']+)',\\s*default:\\s*(.+?)\\s*\\(fallback keys:\\s*\\[.*?\\]\\),\\s*Description:\\s*'(.*)'\\s*$");

    private final FlinkClusterConfig config;
    private final Map<String, List<ConnectorOption>> cache = new ConcurrentHashMap<>();

    public ConnectorOptionCliReader(FlinkClusterConfig config) {
        this.config = config;
    }

    public List<ConnectorOption> readOptions(String pluginType, String plugin) {
        String key = pluginType + ":" + plugin;
        List<ConnectorOption> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        if (config.seatunnelHome() == null) {
            return List.of();
        }
        Path script = Path.of(config.seatunnelHome(), "bin", "seatunnel-connector.sh");
        if (!Files.isExecutable(script)) {
            return List.of();
        }
        ProcessBuilder pb = new ProcessBuilder(script.toString(), "-o", plugin, "-pt", pluginType);
        pb.redirectErrorStream(true);
        if (config.javaHome() != null) {
            pb.environment().put("JAVA_HOME", config.javaHome());
        }
        try {
            Process p = pb.start();
            CompletableFuture<String> outFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "";
                }
            });
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new ConsoleException(ErrorCodes.ENGINE_UNAVAILABLE, "seatunnel-connector.sh timed out for " + plugin);
            }
            String out;
            try {
                out = outFuture.get(2, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException e) {
                out = "";
            }
            if (p.exitValue() != 0) {
                log.debug("seatunnel-connector.sh exited {} for {} {}: {}", p.exitValue(), pluginType, plugin, out);
                return List.of();
            }
            List<ConnectorOption> parsed = parse(out);
            cache.put(key, parsed);
            return parsed;
        } catch (IOException e) {
            log.warn("Could not run seatunnel-connector.sh: {}", e.getMessage());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    public boolean hasConnector(String pluginType, String plugin) {
        return !readOptions(pluginType, plugin).isEmpty();
    }

    static List<ConnectorOption> parse(String output) {
        List<ConnectorOption> options = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return options;
        }
        boolean required = false;
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Required Options:")) {
                required = true;
                continue;
            }
            if (trimmed.startsWith("Optional Options:")) {
                required = false;
                continue;
            }
            Matcher m = OPTION_LINE.matcher(line);
            if (m.matches()) {
                String optKey = m.group(1);
                String def = m.group(2).trim();
                String desc = m.group(3);
                options.add(new ConnectorOption(
                        optKey,
                        null,
                        "null".equals(def) ? null : def,
                        desc,
                        required));
            }
        }
        return options;
    }
}
