package com.tradingbot.controller;

import com.tradingbot.dto.ApiResponse;
import com.tradingbot.dto.InstrumentInfo;
import com.tradingbot.dto.StrategyExecutionResponse;
import com.tradingbot.dto.StrategyRequest;
import com.tradingbot.dto.StrategyTypeInfo;
import com.tradingbot.model.StrategyExecution;
import com.tradingbot.model.StrategyType;
import com.tradingbot.service.StrategyService;
import com.tradingbot.util.ApiConstants;
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
        // Default to ATM_STRADDLE when strategyType is not provided by frontend
        if (request.getStrategyType() == null) {
            request.setStrategyType(StrategyType.ATM_STRADDLE);
        }
        log.info(ApiConstants.LOG_EXECUTE_STRATEGY_REQUEST, request.getStrategyType(), request.getInstrumentType());
        StrategyExecutionResponse response = strategyService.executeStrategy(request);
        log.info(ApiConstants.LOG_EXECUTE_STRATEGY_RESPONSE, response.getExecutionId(), response.getStatus());
        return ResponseEntity.ok(ApiResponse.success(ApiConstants.MSG_STRATEGY_EXECUTED_SUCCESS, response));
    }

    @GetMapping("/active")
    @Operation(summary = "Get all active strategies",
               description = "Fetch all currently active strategy executions being monitored")
    public ResponseEntity<ApiResponse<List<StrategyExecution>>> getActiveStrategies() {
        log.debug(ApiConstants.LOG_GET_ACTIVE_STRATEGIES_REQUEST);
        List<StrategyExecution> strategies = strategyService.getActiveStrategies();
        log.debug(ApiConstants.LOG_GET_ACTIVE_STRATEGIES_RESPONSE, strategies.size());
        return ResponseEntity.ok(ApiResponse.success(strategies));
    }

    @GetMapping("/{executionId}")
    @Operation(summary = "Get strategy execution details by ID",
               description = "Fetch specific strategy execution details including current P&L and status")
    public ResponseEntity<ApiResponse<StrategyExecution>> getStrategy(@PathVariable String executionId) {
        log.debug(ApiConstants.LOG_GET_STRATEGY_REQUEST, executionId);
        StrategyExecution strategy = strategyService.getStrategy(executionId);
        if (strategy == null) {
            log.warn(ApiConstants.LOG_GET_STRATEGY_RESPONSE_NOT_FOUND, executionId);
            return ResponseEntity.notFound().build();
        }
        log.debug(ApiConstants.LOG_GET_STRATEGY_RESPONSE_FOUND, executionId, strategy.getStatus());
        return ResponseEntity.ok(ApiResponse.success(strategy));
    }

    @GetMapping("/types")
    @Operation(summary = "Get available strategy types",
               description = "List all supported strategy types with their implementation status")
    public ResponseEntity<ApiResponse<List<StrategyTypeInfo>>> getStrategyTypes() {
        log.debug(ApiConstants.LOG_GET_STRATEGY_TYPES_REQUEST);
        List<StrategyTypeInfo> types = Arrays.stream(StrategyType.values())
            .map(type -> new StrategyTypeInfo(
                type.name(),
                getStrategyDescription(type),
                isImplemented(type)
            ))
            .collect(Collectors.toList());

        log.debug(ApiConstants.LOG_GET_STRATEGY_TYPES_RESPONSE, types.size());
        return ResponseEntity.ok(ApiResponse.success(types));
    }

    @GetMapping("/instruments")
    @Operation(summary = "Get available instruments",
               description = "Fetch available instruments with their lot sizes and strike intervals")
    public ResponseEntity<ApiResponse<List<InstrumentInfo>>> getInstruments() throws KiteException, IOException {
        log.debug(ApiConstants.LOG_GET_INSTRUMENTS_REQUEST);
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
        log.debug("API Request - Get expiries for instrument: {}", instrumentType);
        List<String> expiries = strategyService.getAvailableExpiries(instrumentType);
        return ResponseEntity.ok(ApiResponse.success(expiries));
    }

    @PostMapping("/stop/{executionId}")
    @Operation(summary = "Stop a running strategy",
               description = "Stop a running strategy by closing all its open positions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stopStrategy(@PathVariable String executionId) throws KiteException {
        Map<String, Object> result = strategyService.stopStrategy(executionId);
        return ResponseEntity.ok(ApiResponse.success("Strategy stopped successfully", result));
    }

    @DeleteMapping("/stop-all")
    @Operation(summary = "Stop all active strategies",
               description = "Stop all active strategies by closing all open positions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stopAllStrategies() throws KiteException {
        Map<String, Object> result = strategyService.stopAllActiveStrategies();
        return ResponseEntity.ok(ApiResponse.success("All active strategies stopped", result));
    }

    private boolean isImplemented(StrategyType type) {
        return type == StrategyType.ATM_STRADDLE || type == StrategyType.ATM_STRANGLE;
    }

    private String getStrategyDescription(StrategyType type) {
        return switch (type) {
            case ATM_STRADDLE -> "Buy 1 ATM Call + Buy 1 ATM Put (Non-directional, profits from high volatility)";
            case ATM_STRANGLE -> "Buy 1 OTM Call + Buy 1 OTM Put (Non-directional, cheaper than Straddle)";
            default -> "Strategy not yet implemented";
        };
    }
}
