package com.tradingbot.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Health", description = "Application health check endpoints")
public class HealthController {

    @GetMapping("/health")
    @Operation(summary = "Get application health status")
    public Map<String, Object> health() {
        return Map.of(
            "status", "UP",
            "timestamp", LocalDateTime.now(),
            "application", "Zerodha Trading Bot",
            "version", "1.0.0",
            "environment", "production"
        );
    }
}
