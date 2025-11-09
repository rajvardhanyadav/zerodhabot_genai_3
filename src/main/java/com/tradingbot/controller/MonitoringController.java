package com.tradingbot.controller;

import com.tradingbot.dto.ApiResponse;
import com.tradingbot.service.strategy.monitoring.PositionMonitor;
import com.tradingbot.service.strategy.monitoring.WebSocketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
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

    @GetMapping("/status")
    @Operation(summary = "Get WebSocket connection status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStatus() {
        try {
            Map<String, Object> status = new HashMap<>();
            status.put("connected", webSocketService.isConnected());
            status.put("activeMonitors", webSocketService.getActiveMonitorsCount());

            return ResponseEntity.ok(ApiResponse.success(status));
        } catch (Exception e) {
            log.error("Error getting monitoring status", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/connect")
    @Operation(summary = "Connect WebSocket for real-time monitoring")
    public ResponseEntity<ApiResponse<String>> connect() {
        try {
            webSocketService.connect();
            return ResponseEntity.ok(ApiResponse.success("WebSocket connection initiated"));
        } catch (Exception e) {
            log.error("Error connecting WebSocket", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/disconnect")
    @Operation(summary = "Disconnect WebSocket")
    public ResponseEntity<ApiResponse<String>> disconnect() {
        try {
            webSocketService.disconnect();
            return ResponseEntity.ok(ApiResponse.success("WebSocket disconnected"));
        } catch (Exception e) {
            log.error("Error disconnecting WebSocket", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/{executionId}")
    @Operation(summary = "Stop monitoring a specific execution")
    public ResponseEntity<ApiResponse<String>> stopMonitoring(@PathVariable String executionId) {
        try {
            webSocketService.stopMonitoring(executionId);
            return ResponseEntity.ok(ApiResponse.success("Monitoring stopped for execution: " + executionId));
        } catch (Exception e) {
            log.error("Error stopping monitoring for execution: {}", executionId, e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}

