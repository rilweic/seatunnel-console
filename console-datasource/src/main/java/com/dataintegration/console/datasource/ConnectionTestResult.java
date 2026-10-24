package com.dataintegration.console.datasource;

/** Outcome of a connection test, shown directly in the datasource form. */
public record ConnectionTestResult(boolean success, String message, long elapsedMillis) {

    public static ConnectionTestResult ok(long elapsedMillis) {
        return new ConnectionTestResult(true, "success", elapsedMillis);
    }

    public static ConnectionTestResult failed(String message, long elapsedMillis) {
        return new ConnectionTestResult(false, message, elapsedMillis);
    }
}
