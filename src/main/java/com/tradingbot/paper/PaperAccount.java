package com.tradingbot.paper;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Paper Trading Account
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaperAccount {

    private String userId;
    private Double availableBalance;
    private Double usedMargin;
    private Double totalBalance;

    // P&L tracking
    @Builder.Default
    private Double totalRealisedPnL = 0.0;
    @Builder.Default
    private Double totalUnrealisedPnL = 0.0;
    @Builder.Default
    private Double todaysPnL = 0.0;

    // Trading statistics
    @Builder.Default
    private Integer totalTrades = 0;
    @Builder.Default
    private Integer winningTrades = 0;
    @Builder.Default
    private Integer losingTrades = 0;
    @Builder.Default
    private Double totalBrokerage = 0.0;
    @Builder.Default
    private Double totalTaxes = 0.0;

    private LocalDateTime createdAt;
    private LocalDateTime lastUpdated;

    /**
     * Create new paper account with initial balance
     */
    public static PaperAccount createNew(String userId, Double initialBalance) {
        LocalDateTime now = LocalDateTime.now();
        return PaperAccount.builder()
                .userId(userId)
                .availableBalance(initialBalance)
                .usedMargin(0.0)
                .totalBalance(initialBalance)
                .totalRealisedPnL(0.0)
                .totalUnrealisedPnL(0.0)
                .todaysPnL(0.0)
                .totalTrades(0)
                .winningTrades(0)
                .losingTrades(0)
                .totalBrokerage(0.0)
                .totalTaxes(0.0)
                .createdAt(now)
                .lastUpdated(now)
                .build();
    }

    /**
     * Check if sufficient balance is available
     */
    public boolean hasSufficientBalance(Double requiredAmount) {
        return availableBalance >= requiredAmount;
    }

    /**
     * Block margin for an order
     */
    public void blockMargin(Double amount) {
        this.availableBalance -= amount;
        this.usedMargin += amount;
        this.lastUpdated = LocalDateTime.now();
    }

    /**
     * Release margin
     */
    public void releaseMargin(Double amount) {
        this.availableBalance += amount;
        this.usedMargin -= amount;
        this.lastUpdated = LocalDateTime.now();
    }

    /**
     * Update P&L
     */
    public void updatePnL(Double realisedPnL, Double unrealisedPnL) {
        this.totalRealisedPnL += realisedPnL;
        this.totalUnrealisedPnL = unrealisedPnL;
        this.todaysPnL = realisedPnL + unrealisedPnL;
        this.totalBalance = availableBalance + usedMargin + unrealisedPnL;
        this.lastUpdated = LocalDateTime.now();
    }

    /**
     * Add brokerage and taxes
     */
    public void addCharges(Double brokerage, Double taxes) {
        this.totalBrokerage += brokerage;
        this.totalTaxes += taxes;
        this.availableBalance -= (brokerage + taxes);
        this.totalBalance -= (brokerage + taxes);
        this.lastUpdated = LocalDateTime.now();
    }

    /**
     * Increment trade statistics
     */
    public void recordTrade(Double pnl) {
        this.totalTrades++;
        if (pnl > 0) {
            this.winningTrades++;
        } else if (pnl < 0) {
            this.losingTrades++;
        }
        this.lastUpdated = LocalDateTime.now();
    }
}