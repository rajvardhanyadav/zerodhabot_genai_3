package com.tradingbot.controller;

import com.tradingbot.dto.ApiResponse;
import com.tradingbot.paper.PaperAccount;
import com.tradingbot.service.UnifiedTradingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Paper Trading Management Controller
 * Provides endpoints to manage and monitor paper trading account
 */
@RestController
@RequestMapping("/api/paper-trading")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Paper Trading", description = "Paper trading management and monitoring endpoints")
public class PaperTradingController {

    private final UnifiedTradingService unifiedTradingService;

    @GetMapping("/status")
    @Operation(summary = "Check if paper trading is enabled",
               description = "Returns whether the application is running in paper trading mode or live mode")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStatus() {
        boolean isPaperMode = unifiedTradingService.isPaperTradingEnabled();
        Map<String, Object> status = Map.of(
            "paperTradingEnabled", isPaperMode,
            "mode", isPaperMode ? "PAPER_TRADING" : "LIVE_TRADING",
            "description", isPaperMode
                ? "Simulated trading with virtual money"
                : "Real trading with actual money"
        );

        return ResponseEntity.ok(ApiResponse.success(status));
    }

    @GetMapping("/account")
    @Operation(summary = "Get paper trading account details",
               description = "Returns account balance, P&L, and trading statistics (only available in paper mode)")
    public ResponseEntity<ApiResponse<PaperAccount>> getAccount() {
        if (!unifiedTradingService.isPaperTradingEnabled()) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("Paper trading is not enabled. Switch to paper mode to access this endpoint."));
        }

        PaperAccount account = unifiedTradingService.getPaperAccount();
        if (account == null) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("Paper account not found"));
        }

        return ResponseEntity.ok(ApiResponse.success("Paper trading account details", account));
    }

    @PostMapping("/account/reset")
    @Operation(summary = "Reset paper trading account",
               description = "Resets the paper trading account to initial state (only available in paper mode)")
    public ResponseEntity<ApiResponse<String>> resetAccount() {
        if (!unifiedTradingService.isPaperTradingEnabled()) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("Paper trading is not enabled. Cannot reset account in live mode."));
        }

        unifiedTradingService.resetPaperAccount();
        log.info("Paper trading account reset successfully");

        return ResponseEntity.ok(ApiResponse.success("Paper trading account reset successfully"));
    }

    @GetMapping("/statistics")
    @Operation(summary = "Get trading statistics",
               description = "Returns detailed trading performance metrics (only available in paper mode)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStatistics() {
        if (!unifiedTradingService.isPaperTradingEnabled()) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("Paper trading is not enabled"));
        }

        PaperAccount account = unifiedTradingService.getPaperAccount();
        if (account == null) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("Paper account not found"));
        }

        Map<String, Object> stats = buildStatisticsMap(account);
        return ResponseEntity.ok(ApiResponse.success("Trading statistics", stats));
    }

    @GetMapping("/info")
    @Operation(summary = "Get comprehensive paper trading information",
               description = "Returns all paper trading related information including mode, account, and configuration")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getInfo() {
        boolean isPaperMode = unifiedTradingService.isPaperTradingEnabled();
        Map<String, Object> info = new HashMap<>();

        info.put("mode", isPaperMode ? "PAPER_TRADING" : "LIVE_TRADING");
        info.put("paperTradingEnabled", isPaperMode);
        info.put("description", isPaperMode
            ? "📊 Paper Trading Mode: All orders are simulated using real-time market data from Kite API"
            : "💰 Live Trading Mode: Orders are placed on actual exchange via Kite API");

        if (isPaperMode) {
            PaperAccount account = unifiedTradingService.getPaperAccount();
            if (account != null) {
                info.put("account", buildAccountInfoMap(account));
            }
        }

        return ResponseEntity.ok(ApiResponse.success(info));
    }

    /**
     * Build statistics map from paper account
     */
    private Map<String, Object> buildStatisticsMap(PaperAccount account) {
        double winRate = account.getTotalTrades() > 0
            ? (account.getWinningTrades() * 100.0 / account.getTotalTrades())
            : 0.0;

        double netPnL = account.getTotalRealisedPnL()
            - account.getTotalBrokerage()
            - account.getTotalTaxes();

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalTrades", account.getTotalTrades());
        stats.put("winningTrades", account.getWinningTrades());
        stats.put("losingTrades", account.getLosingTrades());
        stats.put("winRate", winRate);
        stats.put("totalRealisedPnL", account.getTotalRealisedPnL());
        stats.put("totalUnrealisedPnL", account.getTotalUnrealisedPnL());
        stats.put("todaysPnL", account.getTodaysPnL());
        stats.put("totalBrokerage", account.getTotalBrokerage());
        stats.put("totalTaxes", account.getTotalTaxes());
        stats.put("netPnL", netPnL);
        stats.put("availableBalance", account.getAvailableBalance());
        stats.put("usedMargin", account.getUsedMargin());
        stats.put("totalBalance", account.getTotalBalance());

        return stats;
    }

    /**
     * Build account info map for comprehensive info endpoint
     */
    private Map<String, Object> buildAccountInfoMap(PaperAccount account) {
        return Map.of(
            "userId", account.getUserId(),
            "totalBalance", account.getTotalBalance(),
            "availableBalance", account.getAvailableBalance(),
            "usedMargin", account.getUsedMargin(),
            "totalRealisedPnL", account.getTotalRealisedPnL(),
            "totalUnrealisedPnL", account.getTotalUnrealisedPnL(),
            "totalTrades", account.getTotalTrades(),
            "winningTrades", account.getWinningTrades(),
            "losingTrades", account.getLosingTrades()
        );
    }
}
