package com.tradingbot.controller;

import com.tradingbot.dto.ApiResponse;
import com.tradingbot.service.InstrumentCacheService;
import com.tradingbot.service.RateLimiterService;
import com.tradingbot.service.greeks.DeltaCacheService;
import com.tradingbot.service.strategy.monitoring.WebSocketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller for monitoring active strategy positions
 */
@RestController
@RequestMapping("/api/monitoring")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Position Monitoring", description = "Real-time position monitoring with SL/Target tracking")
public class MonitoringController {

    private final WebSocketService webSocketService;
    private final DeltaCacheService deltaCacheService;
    private final RateLimiterService rateLimiterService;
    private final InstrumentCacheService instrumentCacheService;

    @GetMapping("/status")
    @Operation(summary = "Get WebSocket connection status",
               description = "Check if WebSocket is connected and number of active monitors (for current user)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStatus() {
        Map<String, Object> status = Map.of(
            "connected", webSocketService.isWebSocketConnected(),
            "activeMonitors", webSocketService.getActiveMonitorsCount()
        );
        return ResponseEntity.ok(ApiResponse.success(status));
    }

    @PostMapping("/connect")
    @Operation(summary = "Connect WebSocket for real-time monitoring",
               description = "Establish a per-user WebSocket connection to receive real-time market ticks")
    public ResponseEntity<ApiResponse<String>> connect() {
        if (!webSocketService.isAccessTokenValid()) {
            String msg = "Access token is missing or invalid for this user. Please authenticate via /api/auth/session before connecting WebSocket.";
            return ResponseEntity.badRequest().body(ApiResponse.error(msg));
        }
        webSocketService.connect();
        return ResponseEntity.ok(ApiResponse.success("WebSocket connection initiated for current user"));
    }

    @PostMapping("/disconnect")
    @Operation(summary = "Disconnect WebSocket",
               description = "Close current user's WebSocket connection and stop receiving market ticks")
    public ResponseEntity<ApiResponse<String>> disconnect() {
        webSocketService.disconnect();
        return ResponseEntity.ok(ApiResponse.success("WebSocket disconnected for current user"));
    }

    @DeleteMapping("/{executionId}")
    @Operation(summary = "Stop monitoring a specific execution",
               description = "Stop monitoring a strategy execution without closing positions (current user)")
    public ResponseEntity<ApiResponse<String>> stopMonitoring(@PathVariable String executionId) {
        webSocketService.stopMonitoring(executionId);
        return ResponseEntity.ok(ApiResponse.success("Monitoring stopped for execution: " + executionId));
    }

    @GetMapping("/delta-cache")
    @Operation(summary = "Get Delta Cache Status",
               description = "Returns statistics about the pre-computed delta cache used for HFT optimization. " +
                           "Shows cache size, freshness, and cached ATM strikes for each instrument/expiry pair.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDeltaCacheStatus() {
        Map<String, Object> stats = deltaCacheService.getCacheStats();
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @PostMapping("/delta-cache/refresh")
    @Operation(summary = "Force Delta Cache Refresh",
               description = "Manually trigger a refresh of the delta cache for all supported instruments. " +
                           "Useful before placing orders when cache is stale.")
    public ResponseEntity<ApiResponse<String>> refreshDeltaCache() {
        deltaCacheService.refreshDeltaCache();
        return ResponseEntity.ok(ApiResponse.success("Delta cache refresh triggered for all instruments"));
    }

    @GetMapping("/rate-limiter")
    @Operation(summary = "Get Rate Limiter Status",
               description = "Returns statistics about API rate limiting. Shows current request counts per API type " +
                           "and global request counts within the sliding window. " +
                           "Kite API limits: 3 req/sec per API, 10 req/sec overall.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRateLimiterStatus() {
        Map<String, Object> stats = rateLimiterService.getStatistics();
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/instrument-cache")
    @Operation(summary = "Get Instrument Cache Status",
               description = "Returns statistics about the instrument cache. Shows cached exchanges, " +
                           "instrument counts, and cache freshness. Instruments are cached for 5 minutes " +
                           "to reduce API calls.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getInstrumentCacheStatus() {
        Map<String, Object> stats = instrumentCacheService.getCacheStats();
        return ResponseEntity.ok(ApiResponse.success(stats));
    }
}
