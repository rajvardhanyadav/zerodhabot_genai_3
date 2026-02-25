package com.tradingbot.backtest.controller;

import com.tradingbot.backtest.dto.BacktestRequest;
import com.tradingbot.backtest.dto.BacktestResult;
import com.tradingbot.backtest.engine.BacktestException;
import com.tradingbot.backtest.service.BacktestService;
import com.tradingbot.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * REST controller for the Backtesting API.
 * <p>
 * Completely isolated from live trading — all endpoints operate on historical data only.
 */
@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Backtesting", description = "Strategy backtesting against historical market data")
public class BacktestController {

    private final BacktestService backtestService;

    // ==================== SINGLE DAY ====================

    @PostMapping("/run")
    @Operation(summary = "Run single-day backtest",
               description = "Execute a strategy backtest for a specific day using historical 1-minute candle data from Kite API")
    public ResponseEntity<ApiResponse<BacktestResult>> runBacktest(
            @Valid @RequestBody BacktestRequest request) {

        log.info("Backtest request: date={}, strategy={}, instrument={}",
                request.getBacktestDate(), request.getStrategyType(), request.getInstrumentType());

        BacktestResult result = backtestService.runSingleDay(request);

        String message = result.getStatus() == BacktestResult.BacktestStatus.COMPLETED
                ? "Backtest completed successfully"
                : "Backtest failed: " + result.getErrorMessage();

        return ResponseEntity.ok(ApiResponse.success(message, result));
    }

    // ==================== BATCH ====================

    @PostMapping("/batch")
    @Operation(summary = "Run batch backtest over date range",
               description = "Execute backtests for each trading day in a date range (weekends are skipped)")
    public ResponseEntity<ApiResponse<List<BacktestResult>>> runBatchBacktest(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Valid @RequestBody BacktestRequest request) {

        log.info("Batch backtest request: from={}, to={}, strategy={}", fromDate, toDate, request.getStrategyType());

        List<BacktestResult> results = backtestService.runBatch(fromDate, toDate, request);

        long completed = results.stream()
                .filter(r -> r.getStatus() == BacktestResult.BacktestStatus.COMPLETED)
                .count();
        String message = String.format("Batch backtest completed: %d/%d days processed", completed, results.size());

        return ResponseEntity.ok(ApiResponse.success(message, results));
    }

    // ==================== ASYNC ====================

    @PostMapping("/run-async")
    @Operation(summary = "Run backtest asynchronously",
               description = "Start a backtest in the background. Poll /api/backtest/result/{id} for results.")
    public ResponseEntity<ApiResponse<String>> runAsyncBacktest(
            @Valid @RequestBody BacktestRequest request) {

        log.info("Async backtest request: date={}, strategy={}", request.getBacktestDate(), request.getStrategyType());

        String backtestId = backtestService.runAsync(request);

        return ResponseEntity.ok(ApiResponse.success(
                "Backtest started. Poll /api/backtest/result/" + backtestId + " for results.", backtestId));
    }

    // ==================== RESULT ACCESS ====================

    @GetMapping("/result/{backtestId}")
    @Operation(summary = "Get backtest result by ID",
               description = "Retrieve a completed or in-progress backtest result from cache")
    public ResponseEntity<ApiResponse<BacktestResult>> getResult(@PathVariable String backtestId) {
        BacktestResult result = backtestService.getResult(backtestId);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/results")
    @Operation(summary = "Get all cached backtest results",
               description = "Retrieve all backtest results currently in the in-memory cache")
    public ResponseEntity<ApiResponse<Collection<BacktestResult>>> getAllResults() {
        Collection<BacktestResult> results = backtestService.getAllResults();
        return ResponseEntity.ok(ApiResponse.success(results));
    }

    // ==================== SUPPORTED STRATEGIES ====================

    @GetMapping("/strategies")
    @Operation(summary = "Get supported backtest strategies",
               description = "List all strategy types available for backtesting")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getSupportedStrategies() {
        List<Map<String, Object>> strategies = List.of(
                Map.of("name", "SELL_ATM_STRADDLE",
                       "description", "Sell ATM Call + Put (short straddle) - profits from low volatility and time decay",
                       "backtestSupported", true),
                Map.of("name", "ATM_STRADDLE",
                       "description", "Buy ATM Call + Put (long straddle) - profits from high volatility",
                       "backtestSupported", false),
                Map.of("name", "SHORT_STRANGLE",
                       "description", "Sell OTM CE/PE (~0.4Δ) + Buy Hedge CE/PE (~0.1Δ) - short volatility with defined risk",
                       "backtestSupported", false)
        );
        return ResponseEntity.ok(ApiResponse.success(strategies));
    }

    // ==================== CACHE MANAGEMENT ====================

    @DeleteMapping("/cache")
    @Operation(summary = "Clear backtest result cache",
               description = "Remove all cached backtest results from memory")
    public ResponseEntity<ApiResponse<Void>> clearCache() {
        backtestService.clearCache();
        return ResponseEntity.ok(ApiResponse.success("Cache cleared successfully", null));
    }

    // ==================== EXCEPTION HANDLING ====================

    @ExceptionHandler(BacktestException.class)
    public ResponseEntity<ApiResponse<Void>> handleBacktestException(BacktestException e) {
        log.warn("Backtest error [{}]: {}", e.getErrorCode(), e.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
    }
}

