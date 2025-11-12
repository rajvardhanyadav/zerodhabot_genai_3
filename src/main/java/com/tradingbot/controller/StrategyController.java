package com.tradingbot.controller;

import com.tradingbot.dto.ApiResponse;
import com.tradingbot.dto.InstrumentInfo;
import com.tradingbot.dto.StrategyExecutionResponse;
import com.tradingbot.dto.StrategyRequest;
import com.tradingbot.dto.StrategyTypeInfo;
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
    @Operation(summary = "Execute a trading strategy",
               description = "Execute a configured trading strategy (ATM Straddle, ATM Strangle, etc.)")
    public ResponseEntity<ApiResponse<StrategyExecutionResponse>> executeStrategy(
            @Valid @RequestBody StrategyRequest request) throws KiteException, IOException {
        log.info("Executing strategy: {} for {}", request.getStrategyType(), request.getInstrumentType());
        StrategyExecutionResponse response = strategyService.executeStrategy(request);
        return ResponseEntity.ok(ApiResponse.success("Strategy executed successfully", response));
    }

    @GetMapping("/active")
    @Operation(summary = "Get all active strategies",
               description = "Fetch all currently active strategy executions being monitored")
    public ResponseEntity<ApiResponse<List<StrategyExecution>>> getActiveStrategies() {
        List<StrategyExecution> strategies = strategyService.getActiveStrategies();
        return ResponseEntity.ok(ApiResponse.success(strategies));
    }

    @GetMapping("/{executionId}")
    @Operation(summary = "Get strategy execution details by ID",
               description = "Fetch specific strategy execution details including current P&L and status")
    public ResponseEntity<ApiResponse<StrategyExecution>> getStrategy(@PathVariable String executionId) {
        StrategyExecution strategy = strategyService.getStrategy(executionId);
        if (strategy == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ApiResponse.success(strategy));
    }

    @GetMapping("/types")
    @Operation(summary = "Get available strategy types",
               description = "List all supported strategy types with their implementation status")
    public ResponseEntity<ApiResponse<List<StrategyTypeInfo>>> getStrategyTypes() {
        List<StrategyTypeInfo> types = Arrays.stream(StrategyType.values())
            .map(type -> new StrategyTypeInfo(
                type.name(),
                getStrategyDescription(type),
                isImplemented(type)
            ))
            .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(types));
    }

    @GetMapping("/instruments")
    @Operation(summary = "Get available instruments",
               description = "Fetch available instruments with their lot sizes and strike intervals")
    public ResponseEntity<ApiResponse<List<InstrumentInfo>>> getInstruments() throws KiteException, IOException {
        List<StrategyService.InstrumentDetail> instrumentDetails = strategyService.getAvailableInstruments();

        List<InstrumentInfo> instruments = instrumentDetails.stream()
            .map(detail -> new InstrumentInfo(
                detail.code(),
                detail.name(),
                detail.lotSize(),
                detail.strikeInterval()
            ))
            .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(instruments));
    }

    @GetMapping("/expiries/{instrumentType}")
    @Operation(summary = "Get available expiry dates for an instrument",
               description = "Fetch weekly and monthly expiry dates for specified instrument")
    public ResponseEntity<ApiResponse<List<String>>> getExpiries(@PathVariable String instrumentType)
            throws KiteException, IOException {
        log.info("Fetching expiries for instrument: {}", instrumentType);
        List<String> expiries = strategyService.getAvailableExpiries(instrumentType);
        return ResponseEntity.ok(ApiResponse.success(expiries));
    }

    @DeleteMapping("/stop/{executionId}")
    @Operation(summary = "Stop a specific strategy",
               description = "Close all legs of a strategy execution at market price and mark as completed")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stopStrategy(@PathVariable String executionId)
            throws KiteException, IOException {
        log.info("Request to stop strategy: {}", executionId);
        Map<String, Object> result = strategyService.stopStrategy(executionId);
        return ResponseEntity.ok(ApiResponse.success("Strategy stopped successfully", result));
    }

    @PostMapping("/stop-all")
    @Operation(summary = "Stop all active strategies",
               description = "Close all legs of all active strategy executions at market price")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stopAllStrategies() throws KiteException, IOException {
        log.info("Request to stop all active strategies");
        Map<String, Object> result = strategyService.stopAllActiveStrategies();
        return ResponseEntity.ok(ApiResponse.success("All active strategies stopped", result));
    }

    /**
     * Get strategy description by type
     */
    private String getStrategyDescription(StrategyType type) {
        return switch (type) {
            case ATM_STRADDLE -> "Buy ATM Call + Buy ATM Put (Non-directional strategy with delta-based strike selection)";
            case ATM_STRANGLE -> "Buy OTM Call + Buy OTM Put (Lower cost than straddle)";
            case BULL_CALL_SPREAD -> "Bullish strategy using call options";
            case BEAR_PUT_SPREAD -> "Bearish strategy using put options";
            case IRON_CONDOR -> "Range-bound strategy with limited risk";
            case CUSTOM -> "Custom strategy configuration";
        };
    }

    /**
     * Check if strategy type is implemented
     */
    private boolean isImplemented(StrategyType type) {
        return type == StrategyType.ATM_STRADDLE || type == StrategyType.ATM_STRANGLE;
    }
}
