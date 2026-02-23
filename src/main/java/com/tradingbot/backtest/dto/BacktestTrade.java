package com.tradingbot.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Represents a single simulated trade (one entry→exit cycle) in a backtest.
 * For a straddle, one trade covers both the CE and PE legs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestTrade {

    /** Sequential trade number within the backtest day. */
    private int tradeNumber;

    // ==================== INSTRUMENT INFO ====================

    private String ceSymbol;
    private String peSymbol;
    private double strikePrice;

    // ==================== ENTRY ====================

    private LocalDateTime entryTime;
    private double ceEntryPrice;
    private double peEntryPrice;
    private double combinedEntryPremium;

    // ==================== EXIT ====================

    private LocalDateTime exitTime;
    private double ceExitPrice;
    private double peExitPrice;
    private double combinedExitPremium;

    // ==================== P&L ====================

    /** Quantity per leg (lots × lot size). */
    private int quantity;

    /** P&L in points: for SHORT, (entryPremium - exitPremium). */
    private double pnlPoints;

    /** P&L in INR: pnlPoints × quantity. */
    private double pnlAmount;

    // ==================== EXIT METADATA ====================

    /** Exit reason from PositionMonitorV2 (e.g., CUMULATIVE_TARGET_HIT, TIME_BASED_FORCED_EXIT). */
    private String exitReason;

    /** Whether this trade was an auto-restart continuation. */
    private boolean wasRestarted;
}

