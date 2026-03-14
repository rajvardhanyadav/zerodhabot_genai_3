package com.tradingbot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response after executing a trading strategy")
public class StrategyExecutionResponse {
    @Schema(description = "Unique execution identifier", example = "exec-a1b2c3d4")
    private String executionId;

    @Schema(description = "Execution status", example = "ACTIVE")
    private String status;

    @Schema(description = "Human-readable status message", example = "Strategy executed successfully")
    private String message;

    @Schema(description = "List of individual orders placed as part of this strategy")
    private List<OrderDetail> orders;

    @Schema(description = "Total premium collected/paid at entry (INR)", example = "325.50")
    private Double totalPremium;

    @Schema(description = "Current combined premium value (INR)", example = "290.75")
    private Double currentValue;

    @Schema(description = "Current profit/loss in INR", example = "1737.50")
    private Double profitLoss;

    @Schema(description = "Current profit/loss as percentage of entry premium", example = "10.68")
    private Double profitLossPercentage;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Individual order details within a strategy execution")
    public static class OrderDetail {
        @Schema(description = "Unique order identifier", example = "220303000845481")
        private String orderId;

        @Schema(description = "Trading symbol of the option contract", example = "NIFTY2430621000CE")
        private String tradingSymbol;

        @Schema(description = "Option type: CE (Call) or PE (Put)", example = "CE")
        private String optionType;

        @Schema(description = "Strike price of the option", example = "21000.0")
        private Double strike;

        @Schema(description = "Order quantity", example = "50")
        private Integer quantity;

        @Schema(description = "Execution/limit price (INR)", example = "162.75")
        private Double price;

        @Schema(description = "Order status", example = "COMPLETE")
        private String status;
    }
}
