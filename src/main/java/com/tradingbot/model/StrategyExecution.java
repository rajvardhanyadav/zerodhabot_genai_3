package com.tradingbot.model;

import com.tradingbot.model.StrategyType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StrategyExecution {
    private String executionId;
    private StrategyType strategyType;
    private String instrumentType; // NIFTY, BANKNIFTY
    private String expiry; // Format: yyyy-MM-dd or WEEKLY/MONTHLY
    private String status; // PENDING, EXECUTING, COMPLETED, FAILED
    private String message;
    private Double entryPrice;
    private Double currentPrice;
    private Double profitLoss;
    private Long timestamp;
}

