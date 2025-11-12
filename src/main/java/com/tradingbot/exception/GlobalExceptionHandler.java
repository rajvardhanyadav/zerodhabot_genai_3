package com.tradingbot.exception;

import com.tradingbot.dto.ApiResponse;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.IOException;
import java.time.format.DateTimeParseException;

/**
 * Global exception handler for all REST controllers
 * Centralizes error handling and eliminates duplicate try-catch blocks
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Handle Kite API exceptions
     */
    @ExceptionHandler(KiteException.class)
    public ResponseEntity<ApiResponse<Void>> handleKiteException(KiteException e) {
        log.error("Kite API error: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("Kite API error: " + e.getMessage()));
    }

    /**
     * Handle IO exceptions
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiResponse<Void>> handleIOException(IOException e) {
        log.error("IO error: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("Network error: " + e.getMessage()));
    }

    /**
     * Handle validation errors
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");

        log.error("Validation error: {}", message);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("Validation error: " + message));
    }

    /**
     * Handle illegal argument exceptions (usually business logic errors)
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.error("Invalid argument: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(e.getMessage()));
    }

    /**
     * Handle illegal state exceptions (usually business logic errors)
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalStateException(IllegalStateException e) {
        log.error("Invalid state: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(e.getMessage()));
    }

    /**
     * Handle date parsing exceptions
     */
    @ExceptionHandler(DateTimeParseException.class)
    public ResponseEntity<ApiResponse<Void>> handleDateTimeParseException(DateTimeParseException e) {
        log.error("Date parsing error: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("Invalid date format. Use yyyy-MM-dd (e.g., 2024-01-15)"));
    }

    /**
     * Handle type mismatch exceptions (wrong parameter types)
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatchException(MethodArgumentTypeMismatchException e) {
        String message = String.format("Invalid value '%s' for parameter '%s'",
                e.getValue(), e.getName());
        log.error("Type mismatch: {}", message);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(message));
    }

    /**
     * Handle all other unexpected exceptions
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception e) {
        log.error("Unexpected error: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Unexpected error: " + e.getMessage()));
    }
}

