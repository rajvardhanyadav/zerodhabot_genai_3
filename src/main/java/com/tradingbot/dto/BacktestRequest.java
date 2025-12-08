package com.tradingbot.dto;

import com.tradingbot.model.StrategyType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Request DTO for backtesting a strategy
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BacktestRequest {

    @NotNull(message = "Strategy type is required")
    private StrategyType strategyType;

    @NotNull(message = "Instrument type is required")
    private String instrumentType; // NIFTY, BANKNIFTY, FINNIFTY

    private String expiry; // Format: yyyy-MM-dd or WEEKLY/MONTHLY, if null will use the expiry for the backtest date

    private Integer lots; // Number of lots to trade (default: 1 lot)

    private String orderType; // MARKET or LIMIT (default: MARKET)


    private Double stopLossPoints; // Stop loss in points

    private Double targetPoints; // Target in points

    // Backtesting specific parameters
    private LocalDate backtestDate; // If null, uses latest previous trading day

    private Integer replaySpeedMultiplier; // Speed multiplier for replay (1 = real-time, 0 = fastest, default: 0)

    private Boolean includeDetailedLogs; // Include detailed tick-by-tick logs (default: false)
}


