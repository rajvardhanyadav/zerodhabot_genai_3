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
    private String instrumentType; // NIFTY, BANKNIFTY, FINNIFTY

    @NotNull(message = "Expiry is required")
    private String expiry; // Format: yyyy-MM-dd or WEEKLY/MONTHLY

    private Integer quantity; // Default: 1 lot

    private String orderType; // MARKET or LIMIT (default: MARKET)

    private Double strikeGap; // For strangle strategy (default: 100 for NIFTY, 200 for BANKNIFTY)

    private Boolean autoSquareOff; // Auto square off at 3:15 PM (default: false)

    private Double stopLoss; // Stop loss percentage (optional)

    private Double target; // Target percentage (optional)
}

