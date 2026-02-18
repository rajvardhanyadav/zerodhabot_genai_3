package com.tradingbot.backtest.controller;

import com.tradingbot.backtest.dto.BacktestRequest;
import com.tradingbot.backtest.dto.BacktestResult;
import com.tradingbot.backtest.service.BacktestService;
import com.tradingbot.backtest.strategy.BacktestStrategyFactory;
import com.tradingbot.dto.ApiResponse;
import com.tradingbot.model.StrategyType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * REST Controller for backtesting operations.
 *
 * Provides endpoints for:
 * - Running single-day backtests
 * - Running batch backtests over date ranges
 * - Retrieving backtest results
 * - Querying supported strategies
 *
 * This controller is COMPLETELY ISOLATED from live trading endpoints.
 */
@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Backtesting", description = "Strategy backtesting over historical data")
public class BacktestController {

    private final BacktestService backtestService;
    private final BacktestStrategyFactory strategyFactory;

    @PostMapping("/run")
    @Operation(
            summary = "Run a single-day backtest",
            description = "Execute a strategy backtest for a specific day using historical data. " +
                    "Returns detailed trade-by-trade results and performance metrics."
    )
    public ResponseEntity<ApiResponse<BacktestResult>> runBacktest(
            @Valid @RequestBody BacktestRequest request) {

        log.info("Backtest request received: strategy={}, instrument={}, date={}",
                request.getStrategyType(), request.getInstrumentType(), request.getBacktestDate());

        try {
            BacktestResult result = backtestService.runBacktest(request);
            return ResponseEntity.ok(ApiResponse.success("Backtest completed successfully", result));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid backtest request: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Invalid request: " + e.getMessage()));
        } catch (Exception e) {
            log.error("Backtest execution failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Backtest failed: " + e.getMessage()));
        }
    }

    @PostMapping("/run-async")
    @Operation(
            summary = "Run a backtest asynchronously",
            description = "Start a backtest in the background. Returns immediately with a tracking ID " +
                    "that can be used to poll for results."
    )
    public ResponseEntity<ApiResponse<String>> runBacktestAsync(
            @Valid @RequestBody BacktestRequest request) {

        log.info("Async backtest request received: strategy={}, date={}",
                request.getStrategyType(), request.getBacktestDate());

        try {
            CompletableFuture<BacktestResult> future = backtestService.runBacktestAsync(request);
            String message = "Backtest started. Poll /api/backtest/result/{id} for results.";
            return ResponseEntity.accepted()
                    .body(ApiResponse.success(message, "pending"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Invalid request: " + e.getMessage()));
        }
    }

    @PostMapping("/batch")
    @Operation(
            summary = "Run batch backtest over date range",
            description = "Execute backtests for each trading day in the specified date range. " +
                    "Skips weekends and holidays automatically."
    )
    public ResponseEntity<ApiResponse<List<BacktestResult>>> runBatchBacktest(
            @Valid @RequestBody BacktestRequest request,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "Start date (inclusive)", example = "2025-01-01") LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "End date (inclusive)", example = "2025-01-31") LocalDate toDate) {

        log.info("Batch backtest request: strategy={}, from={}, to={}",
                request.getStrategyType(), fromDate, toDate);

        if (fromDate.isAfter(toDate)) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("From date must be before or equal to to date"));
        }

        try {
            List<BacktestResult> results = backtestService.runBatchBacktest(request, fromDate, toDate);
            String message = String.format("Batch backtest completed: %d days processed", results.size());
            return ResponseEntity.ok(ApiResponse.success(message, results));
        } catch (Exception e) {
            log.error("Batch backtest failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Batch backtest failed: " + e.getMessage()));
        }
    }

    @GetMapping("/result/{backtestId}")
    @Operation(
            summary = "Get backtest result by ID",
            description = "Retrieve a completed backtest result using its unique identifier."
    )
    public ResponseEntity<ApiResponse<BacktestResult>> getResult(
            @PathVariable String backtestId) {

        BacktestResult result = backtestService.getResult(backtestId);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/results")
    @Operation(
            summary = "Get all cached backtest results",
            description = "Retrieve all backtest results currently in the cache."
    )
    public ResponseEntity<ApiResponse<List<BacktestResult>>> getAllResults() {
        List<BacktestResult> results = backtestService.getAllResults();
        return ResponseEntity.ok(ApiResponse.success(results));
    }

    @GetMapping("/strategies")
    @Operation(
            summary = "Get supported backtest strategies",
            description = "List all strategy types that are supported for backtesting."
    )
    public ResponseEntity<ApiResponse<List<StrategyInfo>>> getSupportedStrategies() {
        Set<StrategyType> supportedTypes = strategyFactory.getSupportedTypes();

        List<StrategyInfo> strategies = supportedTypes.stream()
                .map(type -> new StrategyInfo(
                        type.name(),
                        getStrategyDescription(type),
                        true
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(strategies));
    }

    @DeleteMapping("/cache")
    @Operation(
            summary = "Clear backtest result cache",
            description = "Remove all cached backtest results to free memory."
    )
    public ResponseEntity<ApiResponse<String>> clearCache() {
        backtestService.clearCache();
        return ResponseEntity.ok(ApiResponse.success("Cache cleared successfully", null));
    }

    /**
     * Get description for a strategy type.
     */
    private String getStrategyDescription(StrategyType type) {
        return switch (type) {
            case ATM_STRADDLE -> "Buy ATM Call and Put options (long straddle)";
            case SELL_ATM_STRADDLE -> "Sell ATM Call and Put options (short straddle)";
        };
    }

    /**
     * DTO for strategy information.
     */
    public record StrategyInfo(String name, String description, boolean backtestSupported) {}
}

