package com.dataintegration.console.server.web;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.common.Result;
import com.dataintegration.console.engine.ConnectorOption;
import com.dataintegration.console.engine.EngineClient;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Connector option definitions from the cluster (docs/design.md §7.1), cached per connector until
 * the console restarts or {@code refresh=true} after a SeaTunnel upgrade.
 */
@RestController
public class ConnectorController {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]+");

    private final EngineClient engineClient;
    private final Map<String, List<ConnectorOption>> cache = new ConcurrentHashMap<>();

    public ConnectorController(EngineClient engineClient) {
        this.engineClient = engineClient;
    }

    @GetMapping("/api/connectors/{type}/{plugin}/options")
    public Result<List<ConnectorOption>> options(
            @PathVariable String type, @PathVariable String plugin, @RequestParam(defaultValue = "false") boolean refresh) {
        if (!type.equals("source") && !type.equals("sink") && !type.equals("transform")) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "type must be source, sink or transform");
        }
        if (!NAME.matcher(plugin).matches()) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "invalid connector name: " + plugin);
        }
        String key = type + "/" + plugin;
        if (refresh) {
            cache.remove(key);
        }
        return Result.ok(cache.computeIfAbsent(key, k -> engineClient.connectorOptions(type, plugin)));
    }
}
