package com.dataintegration.console.datasource;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;

import java.util.Map;

/** Small helpers for reading datasource properties. */
public final class Props {

    private Props() {}

    public static String required(Map<String, String> props, String key) {
        String value = props.get(key);
        if (value == null || value.isBlank()) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, key + " is required");
        }
        return value.trim();
    }

    public static String optional(Map<String, String> props, String key) {
        String value = props.get(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static int port(Map<String, String> props, int defaultPort) {
        String value = optional(props, "port");
        if (value == null) {
            return defaultPort;
        }
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
            return port;
        } catch (NumberFormatException e) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "port must be between 1 and 65535");
        }
    }

    public static ConsoleException unsupportedConnector(DataSourceType type, String connector) {
        return new ConsoleException(
                ErrorCodes.INVALID_ARGUMENT, type + " datasource cannot be used with connector " + connector);
    }
}
