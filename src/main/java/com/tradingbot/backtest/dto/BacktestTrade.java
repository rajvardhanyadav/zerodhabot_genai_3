package com.tradingbot.backtest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "A single simulated trade (entry→exit cycle) within a backtest. For straddles, covers both CE and PE legs.")
public class BacktestTrade {

    @Schema(description = "Sequential trade number within the backtest day", example = "1")
    private int tradeNumber;

    // ==================== INSTRUMENT INFO ====================

    @Schema(description = "Call option trading symbol", example = "NIFTY2430621050CE")
    private String ceSymbol;

    @Schema(description = "Put option trading symbol", example = "NIFTY2430621050PE")
    private String peSymbol;

    @Schema(description = "ATM strike price", example = "21050.0")
    private double strikePrice;

    // ==================== ENTRY ====================

    @Schema(description = "Entry timestamp", example = "2026-03-10T09:20:00")
    private LocalDateTime entryTime;

    @Schema(description = "Call option entry price (INR)", example = "165.50")
    private double ceEntryPrice;

    @Schema(description = "Put option entry price (INR)", example = "160.25")
    private double peEntryPrice;

    @Schema(description = "Combined CE + PE entry premium (INR)", example = "325.75")
    private double combinedEntryPremium;

    // ==================== EXIT ====================

    @Schema(description = "Exit timestamp", example = "2026-03-10T14:30:00")
    private LocalDateTime exitTime;

    @Schema(description = "Call option exit price (INR)", example = "140.00")
    private double ceExitPrice;

    @Schema(description = "Put option exit price (INR)", example = "145.50")
    private double peExitPrice;

    @Schema(description = "Combined CE + PE exit premium (INR)", example = "285.50")
    private double combinedExitPremium;

    // ==================== P&L ====================

    @Schema(description = "Quantity per leg (lots × lot size)", example = "50")
    private int quantity;

    @Schema(description = "P&L in points: for SHORT, (entryPremium - exitPremium)", example = "40.25")
    private double pnlPoints;

    @Schema(description = "P&L in INR: pnlPoints × quantity", example = "2012.50")
    private double pnlAmount;

    // ==================== EXIT METADATA ====================

    @Schema(description = "Exit reason (e.g., CUMULATIVE_TARGET_HIT, TIME_BASED_FORCED_EXIT)", example = "CUMULATIVE_TARGET_HIT")
    private String exitReason;

    @Schema(description = "Whether this trade was an auto-restart continuation", example = "false")
    private boolean wasRestarted;
}

