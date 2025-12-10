package com.tradingbot.controller;

import com.tradingbot.dto.ApiResponse;
import com.tradingbot.entity.*;
import com.tradingbot.service.UnifiedTradingService;
import com.tradingbot.service.persistence.TradePersistenceService;
import com.tradingbot.util.CurrentUserContext;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/**
 * REST Controller for accessing trading history and analytics.
 * Provides endpoints for querying persisted trading data.
 */
@RestController
@RequestMapping("/api/history")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Trading History", description = "APIs for accessing historical trading data and analytics")
public class TradingHistoryController {

    private final TradePersistenceService persistenceService;
    private final UnifiedTradingService unifiedTradingService;

    // ==================== TRADE HISTORY ====================

    @GetMapping("/trades")
    @Operation(summary = "Get trade history",
               description = "Retrieve trade history for a date range")
    public ResponseEntity<ApiResponse<List<TradeEntity>>> getTradeHistory(
            @Parameter(description = "Start date (yyyy-MM-dd)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "End date (yyyy-MM-dd)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        String userId = CurrentUserContext.getUserId();
        if (userId == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("X-User-Id header is required"));
        }

        log.debug("Fetching trade history for user={} from {} to {}", userId, startDate, endDate);
        List<TradeEntity> trades = persistenceService.getTradesForDateRange(userId, startDate, endDate);

        return ResponseEntity.ok(ApiResponse.success(
                String.format("Found %d trades", trades.size()), trades));
    }

    @GetMapping("/trades/today")
    @Operation(summary = "Get today's trades",
               description = "Retrieve all trades executed today")
    public ResponseEntity<ApiResponse<List<TradeEntity>>> getTodaysTrades() {
        String userId = CurrentUserContext.getUserId();
        if (userId == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("X-User-Id header is required"));
        }

        LocalDate today = LocalDate.now();
        List<TradeEntity> trades = persistenceService.getTradesForDateRange(userId, today, today);

        return ResponseEntity.ok(ApiResponse.success(
                String.format("Found %d trades today", trades.size()), trades));
    }

    // ==================== STRATEGY EXECUTION HISTORY ====================

    @GetMapping("/strategies")
    @Operation(summary = "Get strategy execution history",
               description = "Retrieve all strategy executions for the user")
    public ResponseEntity<ApiResponse<List<StrategyExecutionEntity>>> getStrategyExecutions() {
        String userId = CurrentUserContext.getUserId();
        if (userId == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("X-User-Id header is required"));
        }

        log.debug("Fetching strategy execution history for user={}", userId);
        List<StrategyExecutionEntity> executions = persistenceService.getStrategyExecutionsForUser(userId);

        return ResponseEntity.ok(ApiResponse.success(
                String.format("Found %d strategy executions", executions.size()), executions));
    }

    // ==================== DAILY P&L SUMMARY ====================

    @GetMapping("/daily-summary")
    @Operation(summary = "Get daily P&L summaries",
               description = "Retrieve daily P&L summaries for a date range")
    public ResponseEntity<ApiResponse<List<DailyPnLSummaryEntity>>> getDailySummaries(
            @Parameter(description = "Start date (yyyy-MM-dd)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "End date (yyyy-MM-dd)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        String userId = CurrentUserContext.getUserId();
        if (userId == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("X-User-Id header is required"));
        }

        log.debug("Fetching daily summaries for user={} from {} to {}", userId, startDate, endDate);
        List<DailyPnLSummaryEntity> summaries = persistenceService.getDailySummariesForDateRange(userId, startDate, endDate);

        return ResponseEntity.ok(ApiResponse.success(
                String.format("Found %d daily summaries", summaries.size()), summaries));
    }

    @GetMapping("/daily-summary/today")
    @Operation(summary = "Get today's P&L summary",
               description = "Retrieve P&L summary for today")
    public ResponseEntity<ApiResponse<DailyPnLSummaryEntity>> getTodaysSummary(
            @Parameter(description = "Trading mode (PAPER or LIVE)")
            @RequestParam(defaultValue = "PAPER") String tradingMode) {

        String userId = CurrentUserContext.getUserId();
        if (userId == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("X-User-Id header is required"));
        }

        LocalDate today = LocalDate.now();
        return persistenceService.getDailySummary(userId, today, tradingMode)
                .map(summary -> ResponseEntity.ok(ApiResponse.success("Today's summary", summary)))
                .orElse(ResponseEntity.ok(ApiResponse.success("No trading activity today", null)));
    }

    // ==================== POSITION SNAPSHOTS ====================

    @PostMapping("/position-snapshot")
    @Operation(summary = "Persist position snapshot",
               description = "Manually trigger position snapshot persistence")
    public ResponseEntity<ApiResponse<String>> persistPositionSnapshot() {
        String userId = CurrentUserContext.getUserId();
        if (userId == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("X-User-Id header is required"));
        }

        try {
            unifiedTradingService.persistPositionSnapshot();
            return ResponseEntity.ok(ApiResponse.success(
                    "Position snapshot persisted successfully for user: " + userId, null));
        } catch (KiteException | IOException e) {
            log.error("Failed to persist position snapshot for user={}: {}", userId, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to persist position snapshot: " + e.getMessage()));
        }
    }

    // ==================== TRADING MODE ====================

    @GetMapping("/trading-mode")
    @Operation(summary = "Get current trading mode",
               description = "Get whether the system is in PAPER or LIVE trading mode")
    public ResponseEntity<ApiResponse<String>> getTradingMode() {
        String mode = unifiedTradingService.getTradingMode();
        return ResponseEntity.ok(ApiResponse.success("Current trading mode", mode));
    }
}

