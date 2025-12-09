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
     * Provides detailed logging of Kite API errors for debugging.
     *
     * KiteException fields:
     * - code: Kite API error code (e.g., 403, 500, etc.)
     * - message: Human-readable error message from Kite
     *
     * Common error codes:
     * - 403: Forbidden (invalid API key, expired session)
     * - 429: Rate limit exceeded
     * - 500: Kite server error
     * - 503: Kite service unavailable
     */
    @ExceptionHandler(KiteException.class)
    public ResponseEntity<ApiResponse<Void>> handleKiteException(KiteException e) {
        logKiteExceptionDetails(e);

        HttpStatus status = mapKiteErrorCodeToHttpStatus(e.code);
        String userMessage = buildKiteErrorMessage(e);

        return createErrorResponse(status, userMessage);
    }

    /**
     * Handles RateLimitExceededException from our internal rate limiter.
     * Returns 429 Too Many Requests status to indicate rate limiting.
     */
    @ExceptionHandler(com.tradingbot.service.RateLimiterService.RateLimitExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleRateLimitExceededException(
            com.tradingbot.service.RateLimiterService.RateLimitExceededException e) {
        log.warn("Rate limit exceeded: {}", e.getMessage());
        return createErrorResponse(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    /**
     * Handles RuntimeException - checks if it wraps a KiteException.
     * This is important because KiteException thrown from lambdas/async code
     * often gets wrapped in RuntimeException.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> handleRuntimeException(RuntimeException e) {
        // Check if this RuntimeException wraps a KiteException
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof KiteException kiteEx) {
                log.info("Found wrapped KiteException in RuntimeException, delegating to KiteException handler");
                return handleKiteException(kiteEx);
            }
            cause = cause.getCause();
        }

        // Not a wrapped KiteException, handle as generic runtime exception
        log.error("Runtime exception: {}", e.getMessage(), e);
        return createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Runtime error: " + e.getMessage());
    }

    /**
     * Logs detailed KiteException information for debugging.
     * Captures all available fields from the exception.
     */
    private void logKiteExceptionDetails(KiteException e) {
        log.error("╔═══════════════════════════════════════════════════════════════╗");
        log.error("║              KITE API EXCEPTION DETAILS                       ║");
        log.error("╠═══════════════════════════════════════════════════════════════╣");
        log.error("║ Error Code     : {}", e.code);
        log.error("║ Error Message  : {}", e.message);
        log.error("║ Exception Class: {}", e.getClass().getSimpleName());
        log.error("║ getMessage()   : {}", e.getMessage());
        log.error("║ Cause          : {}", e.getCause() != null ? e.getCause().getMessage() : "null");
        log.error("╠═══════════════════════════════════════════════════════════════╣");
        log.error("║ FULL STACK TRACE:                                             ║");
        log.error("╚═══════════════════════════════════════════════════════════════╝", e);
    }

    /**
     * Maps Kite API error codes to appropriate HTTP status codes.
     */
    private HttpStatus mapKiteErrorCodeToHttpStatus(int kiteErrorCode) {
        return switch (kiteErrorCode) {
            case 400 -> HttpStatus.BAD_REQUEST;         // Bad request
            case 401 -> HttpStatus.UNAUTHORIZED;        // Unauthorized
            case 403 -> HttpStatus.FORBIDDEN;           // Invalid API key or session expired
            case 404 -> HttpStatus.NOT_FOUND;           // Resource not found
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;   // Rate limit exceeded
            case 500 -> HttpStatus.INTERNAL_SERVER_ERROR; // Kite internal error
            case 502, 503, 504 -> HttpStatus.BAD_GATEWAY; // Kite server unavailable
            default -> HttpStatus.BAD_REQUEST;          // Default to client error
        };
    }

    /**
     * Builds a user-friendly error message from KiteException.
     */
    private String buildKiteErrorMessage(KiteException e) {
        StringBuilder sb = new StringBuilder("Kite API error");
        if (e.code > 0) {
            sb.append(" [").append(e.code).append("]");
        }
        if (e.message != null && !e.message.isEmpty()) {
            sb.append(": ").append(e.message);
        } else if (e.getMessage() != null && !e.getMessage().isEmpty()) {
            sb.append(": ").append(e.getMessage());
        }
        return sb.toString();
    }

    /**
     * Handles IO exceptions, typically network-related issues.
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiResponse<Void>> handleIOException(IOException e) {
        log.error("IO error occurred: {}", e.getMessage(), e);
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
     * Checks for wrapped KiteException before logging as generic error.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception e) {
        // Check if this Exception wraps a KiteException anywhere in the chain
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof KiteException kiteEx) {
                log.info("Found wrapped KiteException in Exception chain, delegating to KiteException handler");
                return handleKiteException(kiteEx);
            }
            cause = cause.getCause();
        }

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
