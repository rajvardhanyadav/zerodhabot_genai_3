package com.tradingbot.exception;

import com.tradingbot.dto.ApiResponse;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.IOException;
import java.time.format.DateTimeParseException;

/**
 * Global exception handler for all REST controllers.
 * Centralizes error handling and provides consistent error responses across the application.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static final String DATE_FORMAT_MESSAGE = "Invalid date format. Use yyyy-MM-dd (e.g., 2025-11-16)";
    private static final String MALFORMED_JSON_MESSAGE = "Malformed JSON request. Please check your request body format.";

    /**
     * Handles exceptions from Kite Connect API calls.
     * Returns BAD_REQUEST as these are typically client errors (invalid parameters, auth issues, etc.)
     */
    @ExceptionHandler(KiteException.class)
    public ResponseEntity<ApiResponse<Void>> handleKiteException(KiteException e) {
        log.error("Kite API error: {}", e.getMessage());
        return createErrorResponse(HttpStatus.BAD_REQUEST, "Kite API error: " + e.getMessage());
    }

    /**
     * Handles IO exceptions, typically network-related issues.
     * Returns BAD_REQUEST as these are often connectivity or timeout issues.
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiResponse<Void>> handleIOException(IOException e) {
        log.error("IO error: {}", e.getMessage());
        return createErrorResponse(HttpStatus.BAD_REQUEST, "Network error: " + e.getMessage());
    }

    /**
     * Handles malformed JSON in request body.
     * This occurs when the client sends invalid JSON format.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("Malformed JSON request: {}", e.getMessage());
        return createErrorResponse(HttpStatus.BAD_REQUEST, MALFORMED_JSON_MESSAGE);
    }

    /**
     * Handles validation errors from @Valid annotations on request bodies.
     * Extracts the first validation error message for client feedback.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");

        log.warn("Validation error: {}", message);
        return createErrorResponse(HttpStatus.BAD_REQUEST, "Validation error: " + message);
    }

    /**
     * Handles IllegalArgumentException thrown for invalid business logic parameters.
     * These are typically client errors with invalid input values.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("Invalid argument: {}", e.getMessage());
        return createErrorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * Handles IllegalStateException thrown when an operation cannot be performed.
     * Uses CONFLICT status as these represent state conflicts in business logic.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalStateException(IllegalStateException e) {
        log.warn("Invalid state: {}", e.getMessage());
        return createErrorResponse(HttpStatus.CONFLICT, e.getMessage());
    }

    /**
     * Handles date/time parsing errors from request parameters.
     * Provides user-friendly format guidance.
     */
    @ExceptionHandler(DateTimeParseException.class)
    public ResponseEntity<ApiResponse<Void>> handleDateTimeParseException(DateTimeParseException e) {
        log.warn("Date parsing error: {}", e.getMessage());
        return createErrorResponse(HttpStatus.BAD_REQUEST, DATE_FORMAT_MESSAGE);
    }

    /**
     * Handles type mismatch errors when request parameters cannot be converted.
     * Provides specific feedback about which parameter failed.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatchException(MethodArgumentTypeMismatchException e) {
        String message = String.format("Invalid value '%s' for parameter '%s'", e.getValue(), e.getName());
        log.warn("Type mismatch: {}", message);
        return createErrorResponse(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * Handles operations explicitly not supported in current mode (e.g., paper mode).
     */
    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnsupportedOperation(UnsupportedOperationException e) {
        log.warn("Unsupported operation: {}", e.getMessage());
        return createErrorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * Handles all uncaught exceptions as a safety net.
     * Logs full stack trace for debugging and returns generic error message to client.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception e) {
        log.error("Unexpected error: {}", e.getMessage(), e);
        return createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error: " + e.getMessage());
    }

    /**
     * Creates a standardized error response.
     *
     * @param status  HTTP status code
     * @param message Error message for the client
     * @return ResponseEntity with ApiResponse containing the error
     */
    private static ResponseEntity<ApiResponse<Void>> createErrorResponse(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ApiResponse.error(message));
    }
}
