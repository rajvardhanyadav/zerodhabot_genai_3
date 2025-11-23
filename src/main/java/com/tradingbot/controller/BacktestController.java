package com.tradingbot.controller;

import com.tradingbot.dto.*;
import com.tradingbot.service.BacktestingService;
import com.tradingbot.service.BatchBacktestingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/**
 * Controller for backtesting trading strategies
 */
@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Backtesting", description = "Strategy backtesting endpoints - test strategies on historical data")
public class BacktestController {

    private final BacktestingService backtestingService;
    private final BatchBacktestingService batchBacktestingService;

    @PostMapping("/execute")
    @Operation(
            summary = "Execute a single backtest",
            description = "Run a backtest for a strategy using historical data. " +
                    "By default, uses the latest previous trading day. " +
                    "Requires paper trading mode to be enabled."
    )
    public ResponseEntity<ApiResponse<BacktestResponse>> executeBacktest(
            @Valid @RequestBody BacktestRequest request) throws KiteException, IOException {

        log.info("Backtest request received for strategy: {} / instrument: {} / date: {}",
                request.getStrategyType(), request.getInstrumentType(), request.getBacktestDate());

        BacktestResponse response = backtestingService.executeBacktest(request);

        String message = "COMPLETED".equals(response.getStatus())
                ? "Backtest completed successfully"
                : "Backtest failed";

        return ResponseEntity.ok(ApiResponse.success(message, response));
    }

    @PostMapping("/batch")
    @Operation(
            summary = "Execute multiple backtests",
            description = "Run multiple backtests in parallel or sequentially. " +
                    "Useful for testing different parameters or strategies. " +
                    "Returns aggregate statistics across all backtests."
    )
    public ResponseEntity<ApiResponse<BatchBacktestResponse>> executeBatchBacktest(
            @Valid @RequestBody BatchBacktestRequest request) {

        log.info("Batch backtest request received with {} backtests", request.getBacktests().size());

        BatchBacktestResponse response = batchBacktestingService.executeBatchBacktest(request);

        String message = String.format("Batch backtest completed: %d successful, %d failed",
                response.getSuccessfulBacktests(), response.getFailedBacktests());

        return ResponseEntity.ok(ApiResponse.success(message, response));
    }

    @GetMapping("/{backtestId}")
    @Operation(
            summary = "Get backtest execution details",
            description = "Retrieve detailed results of a specific backtest execution"
    )
    public ResponseEntity<ApiResponse<BacktestingService.BacktestExecution>> getBacktestExecution(
            @PathVariable String backtestId) {

        log.info("Fetching backtest execution: {}", backtestId);

        BacktestingService.BacktestExecution execution = backtestingService.getBacktestExecution(backtestId);

        if (execution == null) {
            log.warn("Backtest execution not found: {}", backtestId);
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(ApiResponse.success(execution));
    }

    @GetMapping("/health")
    @Operation(
            summary = "Backtest service health check",
            description = "Check if backtesting service is available and paper trading mode is enabled"
    )
    public ResponseEntity<ApiResponse<String>> healthCheck() {
        return ResponseEntity.ok(ApiResponse.success("Backtesting service is available"));
    }
}

