package com.tradingbot.dto;

import com.tradingbot.model.StrategyType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyRequest {

    @NotNull(message = "Strategy type is required")
    private StrategyType strategyType;

    @NotNull(message = "Instrument type is required")
    private String instrumentType; // NIFTY, BANKNIFTY, FINNIFTY

    @NotNull(message = "Expiry is required")
    private String expiry; // Format: yyyy-MM-dd or WEEKLY/MONTHLY

    private Integer lots; // Number of lots to trade (default: 1 lot). Will be multiplied by instrument lot size

    private String orderType; // MARKET or LIMIT (default: MARKET)

    private Double strikeGap; // For strangle strategy (default: 100 for NIFTY, 200 for BANKNIFTY)

    private Boolean autoSquareOff; // Auto square off at 3:15 PM (default: false)

    private Double stopLossPoints; // Stop loss in points (optional, uses default from config if not provided)

    private Double targetPoints; // Target in points (optional, uses default from config if not provided)
}
