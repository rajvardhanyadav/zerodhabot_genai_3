package com.tradingbot.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StrategyExecution {
    private String executionId;
    private StrategyType strategyType;
    private String instrumentType; // NIFTY, BANKNIFTY
    private String expiry; // Format: yyyy-MM-dd or WEEKLY/MONTHLY
    private String status; // PENDING, EXECUTING, ACTIVE, COMPLETED, FAILED
    private String message;
    private Double entryPrice;
    private Double currentPrice;
    private Double profitLoss;
    private Long timestamp;

    // Order tracking for stopping strategies
    private List<OrderLeg> orderLegs = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderLeg {
        private String orderId;
        private String tradingSymbol;
        private String optionType; // CE or PE
        private Integer quantity;
        private Double entryPrice;
    }
}
