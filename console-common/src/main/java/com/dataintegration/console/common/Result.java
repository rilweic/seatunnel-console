package com.dataintegration.console.common;

/** Uniform response body for the console HTTP API. {@code code == 0} means success. */
public record Result<T>(int code, String message, T data) {

    public static final int OK = 0;

    public static <T> Result<T> ok(T data) {
        return new Result<>(OK, "success", data);
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }
}
