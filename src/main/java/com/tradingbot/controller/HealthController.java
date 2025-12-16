package com.tradingbot.controller;

import com.tradingbot.service.session.UserSessionManager;
import com.tradingbot.util.CurrentUserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Health", description = "Application health check endpoints")
@RequiredArgsConstructor
public class HealthController {

    private final UserSessionManager sessionManager;

    @GetMapping("/health")
    @Operation(summary = "Get application health status")
    public Map<String, Object> health() {
        return Map.of(
            "status", "UP",
            "timestamp", LocalDateTime.now(),
            "application", "Zerodha Trading Bot",
            "version", "4.1",
            "environment", "production"
        );
    }

    /**
     * Get session diagnostics for Cloud Run debugging.
     * This endpoint helps diagnose session issues across container instances.
     *
     * NOTE: This endpoint does NOT require X-User-Id header as it's for diagnostics.
     */
    @GetMapping("/health/sessions")
    @Operation(summary = "Get session diagnostics (Cloud Run debug)",
               description = "Returns session statistics useful for debugging Cloud Run multi-instance issues. " +
                           "Shows in-memory session count, database session count, and instance information.")
    public Map<String, Object> sessionDiagnostics() {
        Map<String, Object> diagnostics = new LinkedHashMap<>();

        // Instance identification (useful for Cloud Run)
        try {
            diagnostics.put("instanceHostname", InetAddress.getLocalHost().getHostName());
        } catch (Exception e) {
            diagnostics.put("instanceHostname", "unknown");
        }
        diagnostics.put("timestamp", LocalDateTime.now());

        // Session counts
        diagnostics.put("inMemorySessionCount", sessionManager.getActiveSessionCount());
        diagnostics.put("databaseSessionCount", sessionManager.getDatabaseSessionCount());
        diagnostics.put("activeUserIds", sessionManager.getActiveUserIds());

        // Current request context (if header was provided)
        String currentUserId = CurrentUserContext.getUserId();
        if (currentUserId != null) {
            diagnostics.put("currentUserId", currentUserId);
            diagnostics.put("currentUserHasInMemorySession", sessionManager.hasSession(currentUserId));
            Map<String, Object> sessionInfo = sessionManager.getSessionInfo(currentUserId);
            if (sessionInfo != null) {
                diagnostics.put("currentUserSessionInfo", sessionInfo);
            }
        } else {
            diagnostics.put("currentUserId", "not provided (no X-User-Id header)");
        }

        // Cloud Run environment indicators
        diagnostics.put("cloudRunInstance", System.getenv("K_REVISION") != null);
        diagnostics.put("kRevision", System.getenv("K_REVISION"));
        diagnostics.put("kService", System.getenv("K_SERVICE"));

        return diagnostics;
    }
}
