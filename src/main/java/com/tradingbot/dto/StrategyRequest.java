package com.tradingbot.dto;

import com.tradingbot.model.StrategyType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StrategyRequest {

    @NotNull(message = "Strategy type is required")
    private StrategyType strategyType;

    @NotNull(message = "Instrument type is required")
    private String instrumentType; // NIFTY, BANKNIFTY

    @NotNull(message = "Expiry is required")
    private String expiry; // Format: yyyy-MM-dd or WEEKLY/MONTHLY

    private Integer lots; // Number of lots to trade (default: 1 lot). Will be multiplied by instrument lot size

    private String orderType; // MARKET or LIMIT (default: MARKET)


    private Boolean autoSquareOff; // Auto square off at 3:15 PM (default: false)

    private Double stopLossPoints; // Stop loss in points (optional, uses default from config if not provided)

    private Double targetPoints; // Target in points (optional, uses default from config if not provided)

    // ==================== PREMIUM-BASED EXIT PARAMETERS ====================
    // These fields enable dynamic premium-based exits when provided.
    // If not provided (null), falls back to values from strategy configuration defaults.

    /**
     * Target decay percentage for premium-based exit.
     * Exit (profit) when combined LTP <= entryPremium * (1 - targetDecayPct).
     * <p>
     * Accepts both formats:
     * <ul>
     *   <li>Whole percentages (1-100): e.g., 5 for 5%</li>
     *   <li>Decimal fractions (0.01-1.0): e.g., 0.05 for 5%</li>
     * </ul>
     * If null, uses value from StrategyConfig.targetDecayPct.
     */
    private Double targetDecayPct;

    /**
     * Stop loss expansion percentage for premium-based exit.
     * Exit (loss) when combined LTP >= entryPremium * (1 + stopLossExpansionPct).
     * <p>
     * Accepts both formats:
     * <ul>
     *   <li>Whole percentages (1-100): e.g., 10 for 10%</li>
     *   <li>Decimal fractions (0.01-1.0): e.g., 0.10 for 10%</li>
     * </ul>
     * If null, uses value from StrategyConfig.stopLossExpansionPct.
     */
    private Double stopLossExpansionPct;
}
