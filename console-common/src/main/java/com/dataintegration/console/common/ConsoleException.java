package com.dataintegration.console.common;

/** Business exception carrying an error code that is returned to the caller as-is. */
public class ConsoleException extends RuntimeException {

    private final int code;

    public ConsoleException(int code, String message) {
        super(message);
        this.code = code;
    }

    public ConsoleException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
