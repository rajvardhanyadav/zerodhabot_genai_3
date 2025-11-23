package com.tradingbot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Response DTO for backtesting results
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BacktestResponse {

    // Execution details
    private String backtestId;
    private String strategyType;
    private String instrumentType;
    private LocalDate backtestDate;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long durationMs;

    // Strategy execution details
    private String executionId;
    private String status; // RUNNING, COMPLETED, FAILED
    private String completionReason; // STOP_LOSS_HIT, TARGET_HIT, etc.

    // Entry details
    private Double spotPriceAtEntry;
    private Double atmStrike;
    private List<LegDetail> legs;

    // Performance metrics
    private PerformanceMetrics performanceMetrics;

    // Trade details
    private List<TradeEvent> tradeEvents;

    // Error details if failed
    private String errorMessage;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LegDetail {
        private String tradingSymbol;
        private String optionType; // CE or PE
        private Double strike;
        private Integer quantity;
        private Double entryPrice;
        private Double exitPrice;
        private LocalDateTime entryTime;
        private LocalDateTime exitTime;
        private Double profitLoss;
        private Double profitLossPercentage;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PerformanceMetrics {
        private Double totalPremiumPaid; // Total entry cost
        private Double totalPremiumReceived; // Total exit value
        private Double grossProfitLoss;
        private Double charges; // Brokerage, STT, etc.
        private Double netProfitLoss;
        private Double returnPercentage;
        private Double returnOnInvestment; // ROI
        private Double maxDrawdown; // Maximum loss during the trade
        private Double maxProfit; // Maximum profit during the trade
        private Integer numberOfTrades; // Total number of orders executed
        private Long holdingDurationMs; // Time from entry to complete exit
        private String holdingDurationFormatted; // Human readable duration
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TradeEvent {
        private LocalDateTime timestamp;
        private String eventType; // ENTRY, EXIT, STOP_LOSS, TARGET, PRICE_UPDATE
        private String description;
        private Map<String, Double> prices; // Current prices at this event
        private Double totalValue;
        private Double unrealizedPnL;
    }
}

