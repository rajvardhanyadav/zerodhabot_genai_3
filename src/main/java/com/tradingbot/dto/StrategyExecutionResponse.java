package com.tradingbot.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StrategyExecutionResponse {
    private String executionId;
    private String status;
    private String message;
    private List<OrderDetail> orders;
    private Double totalPremium;
    private Double currentValue;
    private Double profitLoss;
    private Double profitLossPercentage;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderDetail {
        private String orderId;
        private String tradingSymbol;
        private String optionType; // CE or PE
        private Double strike;
        private Integer quantity;
        private Double price;
        private String status;
    }
}

