package com.tradingbot.controller;

import com.tradingbot.dto.ApiResponse;
import com.tradingbot.dto.StrategyExecutionResponse;
import com.tradingbot.dto.StrategyRequest;
import com.tradingbot.model.StrategyExecution;
import com.tradingbot.model.StrategyType;
import com.tradingbot.service.StrategyService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/strategies")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Trading Strategies", description = "Automated trading strategy execution endpoints")
public class StrategyController {

    private final StrategyService strategyService;

    @PostMapping("/execute")
    @Operation(summary = "Execute a trading strategy")
    public ResponseEntity<ApiResponse<StrategyExecutionResponse>> executeStrategy(
            @Valid @RequestBody StrategyRequest request) {
        try {
            log.error("Executing strategy: {} for {}", request.getStrategyType(), request.getInstrumentType());
            StrategyExecutionResponse response = strategyService.executeStrategy(request);
            return ResponseEntity.ok(ApiResponse.success("Strategy executed successfully", response));
        } catch (KiteException | IOException e) {
            log.error("Error executing strategy", e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Strategy execution failed: " + e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error executing strategy", e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Unexpected error: " + e.getMessage()));
        }
    }

    @GetMapping("/active")
    @Operation(summary = "Get all active strategies")
    public ResponseEntity<ApiResponse<List<StrategyExecution>>> getActiveStrategies() {
        try {
            List<StrategyExecution> strategies = strategyService.getActiveStrategies();
            return ResponseEntity.ok(ApiResponse.success(strategies));
        } catch (Exception e) {
            log.error("Error fetching active strategies", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/{executionId}")
    @Operation(summary = "Get strategy execution details by ID")
    public ResponseEntity<ApiResponse<StrategyExecution>> getStrategy(@PathVariable String executionId) {
        try {
            StrategyExecution strategy = strategyService.getStrategy(executionId);
            if (strategy == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(ApiResponse.success(strategy));
        } catch (Exception e) {
            log.error("Error fetching strategy details", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/types")
    @Operation(summary = "Get available strategy types")
    public ResponseEntity<ApiResponse<List<StrategyTypeInfo>>> getStrategyTypes() {
        try {
            List<StrategyTypeInfo> types = Arrays.stream(StrategyType.values())
                .map(type -> new StrategyTypeInfo(
                    type.name(),
                    getStrategyDescription(type),
                    isImplemented(type)
                ))
                .collect(Collectors.toList());

            return ResponseEntity.ok(ApiResponse.success(types));
        } catch (Exception e) {
            log.error("Error fetching strategy types", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/instruments")
    @Operation(summary = "Get available instruments")
    public ResponseEntity<ApiResponse<List<InstrumentInfo>>> getInstruments() {
        try {
            // Fetch instruments dynamically from Kite API with cached lot sizes
            List<StrategyService.InstrumentDetail> instrumentDetails = strategyService.getAvailableInstruments();

            // Convert to controller's InstrumentInfo format
            List<InstrumentInfo> instruments = instrumentDetails.stream()
                .map(detail -> new InstrumentInfo(
                    detail.code(),
                    detail.name(),
                    detail.lotSize(),
                    detail.strikeInterval()
                ))
                .collect(Collectors.toList());

            return ResponseEntity.ok(ApiResponse.success(instruments));
        } catch (KiteException | IOException e) {
            log.error("Error fetching instruments from Kite API", e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed to fetch instruments: " + e.getMessage()));
        } catch (Exception e) {
            log.error("Error fetching instruments", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/expiries/{instrumentType}")
    @Operation(summary = "Get available expiry dates for an instrument")
    public ResponseEntity<ApiResponse<List<String>>> getExpiries(@PathVariable String instrumentType) {
        try {
            log.info("Fetching expiries for instrument: {}", instrumentType);
            List<String> expiries = strategyService.getAvailableExpiries(instrumentType);
            return ResponseEntity.ok(ApiResponse.success(expiries));
        } catch (KiteException | IOException e) {
            log.error("Error fetching expiries for {}", instrumentType, e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed to fetch expiries: " + e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error fetching expiries", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/stop/{executionId}")
    @Operation(summary = "Stop a specific strategy by closing all legs at market price")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stopStrategy(@PathVariable String executionId) {
        try {
            log.info("Request to stop strategy: {}", executionId);
            Map<String, Object> result = strategyService.stopStrategy(executionId);
            return ResponseEntity.ok(ApiResponse.success("Strategy stopped successfully", result));
        } catch (IllegalArgumentException e) {
            log.error("Strategy not found: {}", executionId);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        } catch (IllegalStateException e) {
            log.error("Invalid strategy state: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        } catch (KiteException | IOException e) {
            log.error("Error stopping strategy: {}", executionId, e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed to stop strategy: " + e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error stopping strategy", e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Unexpected error: " + e.getMessage()));
        }
    }

    @DeleteMapping("/stop-all")
    @Operation(summary = "Stop all active strategies by closing all legs at market price")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stopAllStrategies() {
        try {
            log.info("Request to stop all active strategies");
            Map<String, Object> result = strategyService.stopAllActiveStrategies();
            return ResponseEntity.ok(ApiResponse.success("All active strategies stopped", result));
        } catch (KiteException | IOException e) {
            log.error("Error stopping all strategies", e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed to stop strategies: " + e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error stopping all strategies", e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Unexpected error: " + e.getMessage()));
        }
    }

    // Helper method to get strategy description
    private String getStrategyDescription(StrategyType type) {
        return switch (type) {
            case ATM_STRADDLE -> "Buy ATM Call + Buy ATM Put (Non-directional strategy)";
            case ATM_STRANGLE -> "Buy OTM Call + Buy OTM Put (Lower cost than straddle)";
            case BULL_CALL_SPREAD -> "Bullish strategy using call options";
            case BEAR_PUT_SPREAD -> "Bearish strategy using put options";
            case IRON_CONDOR -> "Range-bound strategy with limited risk";
            case CUSTOM -> "Custom strategy configuration";
        };
    }

    // Helper method to check if strategy is implemented
    private boolean isImplemented(StrategyType type) {
        return type == StrategyType.ATM_STRADDLE || type == StrategyType.ATM_STRANGLE;
    }

    // DTOs for response
    public record StrategyTypeInfo(String name, String description, boolean implemented) {}

    public record InstrumentInfo(String code, String name, int lotSize, double strikeInterval) {}
}
