package com.tradingbot.controller;

import com.tradingbot.dto.ApiResponse;
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

    @GetMapping("/status")
    @Operation(summary = "Get WebSocket connection status",
               description = "Check if WebSocket is connected and number of active monitors")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStatus() {
        Map<String, Object> status = Map.of(
            "connected", webSocketService.isConnected(),
            "activeMonitors", webSocketService.getActiveMonitorsCount()
        );
        return ResponseEntity.ok(ApiResponse.success(status));
    }

    @PostMapping("/connect")
    @Operation(summary = "Connect WebSocket for real-time monitoring",
               description = "Establish WebSocket connection to receive real-time market ticks")
    public ResponseEntity<ApiResponse<String>> connect() {
        webSocketService.connect();
        return ResponseEntity.ok(ApiResponse.success("WebSocket connection initiated"));
    }

    @PostMapping("/disconnect")
    @Operation(summary = "Disconnect WebSocket",
               description = "Close WebSocket connection and stop receiving market ticks")
    public ResponseEntity<ApiResponse<String>> disconnect() {
        webSocketService.disconnect();
        return ResponseEntity.ok(ApiResponse.success("WebSocket disconnected"));
    }

    @DeleteMapping("/{executionId}")
    @Operation(summary = "Stop monitoring a specific execution",
               description = "Stop monitoring a strategy execution without closing positions")
    public ResponseEntity<ApiResponse<String>> stopMonitoring(@PathVariable String executionId) {
        webSocketService.stopMonitoring(executionId);
        return ResponseEntity.ok(ApiResponse.success("Monitoring stopped for execution: " + executionId));
    }
}
