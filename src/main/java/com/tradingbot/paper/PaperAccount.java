package com.tradingbot.paper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Paper Trading Account
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaperAccount {

    private String userId;
    private Double availableBalance;
    private Double usedMargin;
    private Double totalBalance;

    // P&L tracking
    private Double totalRealisedPnL;
    private Double totalUnrealisedPnL;
    private Double todaysPnL;

    // Trading statistics
    private Integer totalTrades;
    private Integer winningTrades;
    private Integer losingTrades;
    private Double totalBrokerage;
    private Double totalTaxes;

    private LocalDateTime createdAt;
    private LocalDateTime lastUpdated;

    /**
     * Create new paper account with initial balance
     */
    public static PaperAccount createNew(String userId, Double initialBalance) {
        PaperAccount account = new PaperAccount();
        account.setUserId(userId);
        account.setAvailableBalance(initialBalance);
        account.setUsedMargin(0.0);
        account.setTotalBalance(initialBalance);
        account.setTotalRealisedPnL(0.0);
        account.setTotalUnrealisedPnL(0.0);
        account.setTodaysPnL(0.0);
        account.setTotalTrades(0);
        account.setWinningTrades(0);
        account.setLosingTrades(0);
        account.setTotalBrokerage(0.0);
        account.setTotalTaxes(0.0);
        account.setCreatedAt(LocalDateTime.now());
        account.setLastUpdated(LocalDateTime.now());
        return account;
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
}