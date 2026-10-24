package com.dataintegration.console.server.web;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.common.Result;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ConsoleException.class)
    public ResponseEntity<Result<Void>> handleConsole(ConsoleException e) {
        log.warn("Request failed: [{}] {}", e.getCode(), e.getMessage());
        return ResponseEntity.status(statusOf(e.getCode())).body(Result.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message =
                e.getBindingResult().getFieldErrors().stream()
                        .map(f -> f.getField() + " " + f.getDefaultMessage())
                        .findFirst()
                        .orElse("invalid request");
        return ResponseEntity.badRequest().body(Result.fail(ErrorCodes.INVALID_ARGUMENT, message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Result.fail(ErrorCodes.INVALID_ARGUMENT, "malformed request body"));
    }

    /** Unique-key violations, e.g. a live project with the same name. */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<Void>> handleDuplicate(DuplicateKeyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Result.fail(ErrorCodes.CONFLICT, "name already exists"));
    }

    /** A request parameter of the wrong type, e.g. a page number that is not a number. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(Result.fail(ErrorCodes.INVALID_ARGUMENT, "invalid value for " + e.getName()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception e) {
        // Spring's own request errors (no such path, wrong method, missing parameter, ...) carry
        // their HTTP status: they are the client's mistake, answered as such and not logged as errors.
        if (e instanceof ErrorResponse response && response.getStatusCode().is4xxClientError()) {
            HttpStatusCode status = response.getStatusCode();
            log.debug("Request rejected with {}: {}", status, e.getMessage());
            int code = status.value() == 404 ? ErrorCodes.NOT_FOUND : ErrorCodes.INVALID_ARGUMENT;
            String message = status.value() == 404 ? "not found" : e.getMessage();
            return ResponseEntity.status(status).body(Result.fail(code, message));
        }
        log.error("Unexpected error", e);
        return ResponseEntity.internalServerError()
                .body(Result.fail(ErrorCodes.INTERNAL_ERROR, "internal error"));
    }

    static HttpStatus statusOf(int code) {
        return switch (code) {
            case ErrorCodes.UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case ErrorCodes.FORBIDDEN -> HttpStatus.FORBIDDEN;
            case ErrorCodes.NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ErrorCodes.CONFLICT -> HttpStatus.CONFLICT;
            case ErrorCodes.ENGINE_UNAVAILABLE, ErrorCodes.ENGINE_RESPONSE_LOST, ErrorCodes.SCHEDULER_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case ErrorCodes.INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
