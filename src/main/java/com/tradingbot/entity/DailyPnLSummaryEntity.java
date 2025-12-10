package com.tradingbot.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Entity representing a daily P&L summary for a user.
 * Aggregates all trading activity for a single day.
 */
@Entity
@Table(name = "daily_pnl_summary", indexes = {
    @Index(name = "idx_daily_user_date", columnList = "userId, tradingDate", unique = true),
    @Index(name = "idx_daily_date", columnList = "tradingDate")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyPnLSummaryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String userId;

    @Column(nullable = false)
    private LocalDate tradingDate;

    // P&L Summary
    @Column(precision = 15, scale = 4)
    @Builder.Default
    private BigDecimal realizedPnl = BigDecimal.ZERO;

    @Column(precision = 15, scale = 4)
    @Builder.Default
    private BigDecimal unrealizedPnl = BigDecimal.ZERO;

    @Column(precision = 15, scale = 4)
    @Builder.Default
    private BigDecimal grossPnl = BigDecimal.ZERO;

    @Column(precision = 15, scale = 4)
    @Builder.Default
    private BigDecimal netPnl = BigDecimal.ZERO; // After charges

    // Charges breakdown
    @Column(precision = 12, scale = 4)
    @Builder.Default
    private BigDecimal totalBrokerage = BigDecimal.ZERO;

    @Column(precision = 12, scale = 4)
    @Builder.Default
    private BigDecimal totalStt = BigDecimal.ZERO;

    @Column(precision = 12, scale = 4)
    @Builder.Default
    private BigDecimal totalExchangeCharges = BigDecimal.ZERO;

    @Column(precision = 12, scale = 4)
    @Builder.Default
    private BigDecimal totalGst = BigDecimal.ZERO;

    @Column(precision = 12, scale = 4)
    @Builder.Default
    private BigDecimal totalCharges = BigDecimal.ZERO;

    // Trade statistics
    @Column
    @Builder.Default
    private Integer totalTrades = 0;

    @Column
    @Builder.Default
    private Integer winningTrades = 0;

    @Column
    @Builder.Default
    private Integer losingTrades = 0;

    @Column
    @Builder.Default
    private Integer breakEvenTrades = 0;

    // Win rate (stored for quick access)
    @Column(precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal winRate = BigDecimal.ZERO;

    // Average P&L per trade
    @Column(precision = 15, scale = 4)
    @Builder.Default
    private BigDecimal avgPnlPerTrade = BigDecimal.ZERO;

    // Best and worst trades
    @Column(precision = 15, scale = 4)
    private BigDecimal bestTradePnl;

    @Column(precision = 15, scale = 4)
    private BigDecimal worstTradePnl;

    // Strategy execution counts
    @Column
    @Builder.Default
    private Integer strategyExecutions = 0;

    @Column
    @Builder.Default
    private Integer successfulStrategies = 0;

    @Column
    @Builder.Default
    private Integer failedStrategies = 0;

    // Volume
    @Column(precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal totalTurnover = BigDecimal.ZERO;

    @Column
    @Builder.Default
    private Integer totalQuantityTraded = 0;

    // Trading mode (can have both PAPER and LIVE entries per day)
    @Column(nullable = false, length = 10)
    private String tradingMode; // PAPER or LIVE

    // Market context (optional - for correlation analysis)
    @Column(precision = 15, scale = 4)
    private BigDecimal niftyOpen;

    @Column(precision = 15, scale = 4)
    private BigDecimal niftyClose;

    @Column(precision = 10, scale = 4)
    private BigDecimal niftyChangePercent;

    // Account balance at end of day
    @Column(precision = 18, scale = 4)
    private BigDecimal endOfDayBalance;

    // Audit
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        calculateDerivedFields();
    }

    /**
     * Calculate derived fields like win rate and average P&L
     */
    public void calculateDerivedFields() {
        if (totalTrades != null && totalTrades > 0) {
            winRate = BigDecimal.valueOf(winningTrades)
                    .divide(BigDecimal.valueOf(totalTrades), 4, java.math.RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            if (netPnl != null) {
                avgPnlPerTrade = netPnl.divide(BigDecimal.valueOf(totalTrades), 4, java.math.RoundingMode.HALF_UP);
            }
        }
    }

    /**
     * Increment trade count and update P&L
     */
    public void recordTrade(BigDecimal tradePnl, BigDecimal charges) {
        totalTrades++;

        if (tradePnl.compareTo(BigDecimal.ZERO) > 0) {
            winningTrades++;
            if (bestTradePnl == null || tradePnl.compareTo(bestTradePnl) > 0) {
                bestTradePnl = tradePnl;
            }
        } else if (tradePnl.compareTo(BigDecimal.ZERO) < 0) {
            losingTrades++;
            if (worstTradePnl == null || tradePnl.compareTo(worstTradePnl) < 0) {
                worstTradePnl = tradePnl;
            }
        } else {
            breakEvenTrades++;
        }

        realizedPnl = realizedPnl.add(tradePnl);
        totalCharges = totalCharges.add(charges);
        netPnl = realizedPnl.subtract(totalCharges);

        calculateDerivedFields();
    }
}

