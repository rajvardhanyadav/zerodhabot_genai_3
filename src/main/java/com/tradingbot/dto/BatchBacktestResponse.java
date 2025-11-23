package com.tradingbot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response DTO for batch backtesting results
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BatchBacktestResponse {

    private String batchId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long totalDurationMs;
    private Integer totalBacktests;
    private Integer successfulBacktests;
    private Integer failedBacktests;

    // Individual backtest results
    private List<BacktestResponse> results;

    // Aggregate statistics
    private AggregateStatistics aggregateStatistics;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AggregateStatistics {
        private Double totalNetPnL;
        private Double averageNetPnL;
        private Double totalReturnPercentage;
        private Double averageReturnPercentage;
        private Integer totalWins;
        private Integer totalLosses;
        private Double winRate; // Percentage
        private Double bestReturn;
        private Double worstReturn;
        private Double averageHoldingDurationMs;
        private String averageHoldingDurationFormatted;
    }
}

