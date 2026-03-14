package com.tradingbot.dto;

import com.tradingbot.model.StrategyType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to execute an automated trading strategy")
public class StrategyRequest {

    @NotNull(message = "Strategy type is required")
    @Schema(description = "Type of strategy to execute", example = "SELL_ATM_STRADDLE", requiredMode = Schema.RequiredMode.REQUIRED)
    private StrategyType strategyType;

    @NotNull(message = "Instrument type is required")
    @Schema(description = "Underlying index instrument", example = "NIFTY", requiredMode = Schema.RequiredMode.REQUIRED)
    private String instrumentType;

    @NotNull(message = "Expiry is required")
    @Schema(description = "Expiry date for options contracts (yyyy-MM-dd or WEEKLY/MONTHLY)", example = "2026-03-19", requiredMode = Schema.RequiredMode.REQUIRED)
    private String expiry;

    @Schema(description = "Number of lots to trade (multiplied by instrument lot size, default: 1)", example = "1")
    private Integer lots;

    @Schema(description = "Order type: MARKET or LIMIT (default: MARKET)", example = "MARKET")
    private String orderType;

    @Schema(description = "Enable auto square-off at 3:15 PM", example = "true")
    private Boolean autoSquareOff;

    @Schema(description = "Stop loss in absolute points (used when slTargetMode=points)", example = "50.0")
    private Double stopLossPoints;

    @Schema(description = "Target in absolute points (used when slTargetMode=points)", example = "50.0")
    private Double targetPoints;

    @Schema(description = "Target decay percentage for premium-based exit. Exit when combined LTP <= entryPremium × (1 - targetDecayPct). Accepts 1-100 or 0.01-1.0", example = "5.0")
    private Double targetDecayPct;

    @Schema(description = "Stop loss expansion percentage for premium-based exit. Exit when combined LTP >= entryPremium × (1 + stopLossExpansionPct). Accepts 1-100 or 0.01-1.0", example = "10.0")
    private Double stopLossExpansionPct;

    @Schema(description = "SL/Target calculation mode: 'points' for fixed-point exits, 'percentage' for premium-based exits", example = "points")
    private String slTargetMode;

    @Schema(description = "Enable hedge legs for sell strategies (buys 0.1 delta OTM CE+PE as protection)", example = "false")
    private Boolean hedgeEnabled;
}
