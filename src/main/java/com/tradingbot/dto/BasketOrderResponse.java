package com.tradingbot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Response DTO for basket order placement
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response after placing a basket of orders")
public class BasketOrderResponse {

    @Schema(description = "Overall basket status: SUCCESS, PARTIAL, or FAILED", example = "SUCCESS")
    private String status;

    @Schema(description = "Overall result message", example = "All 2 orders placed successfully")
    private String message;

    @Schema(description = "List of individual order results")
    @Builder.Default
    private List<BasketOrderResult> orderResults = new ArrayList<>();

    @Schema(description = "Count of successfully placed orders", example = "2")
    private int successCount;

    @Schema(description = "Count of failed orders", example = "0")
    private int failureCount;

    @Schema(description = "Total number of orders in the basket", example = "2")
    private int totalOrders;

    /**
     * Individual order result within a basket response
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "Result of an individual order within a basket")
    public static class BasketOrderResult {
        @Schema(description = "Unique order identifier", example = "220303000845481")
        private String orderId;

        @Schema(description = "Trading symbol", example = "NIFTY2430621000CE")
        private String tradingSymbol;

        @Schema(description = "Leg type identifier", example = "CALL")
        private String legType;

        @Schema(description = "Order placement status: SUCCESS or FAILED", example = "SUCCESS")
        private String status;

        @Schema(description = "Status message or error details", example = "Order placed successfully")
        private String message;

        @Schema(description = "Actual execution price (INR)", example = "162.75")
        private Double executionPrice;

        @Schema(description = "Instrument token for WebSocket monitoring", example = "12345678")
        private Long instrumentToken;
    }

    /**
     * Check if all orders were successful
     */
    public boolean isAllSuccess() {
        return failureCount == 0 && successCount == totalOrders;
    }

    /**
     * Check if any order was successful
     */
    public boolean hasAnySuccess() {
        return successCount > 0;
    }

    /**
     * Get order IDs for successful orders
     */
    public List<String> getSuccessfulOrderIds() {
        return orderResults.stream()
                .filter(r -> "SUCCESS".equals(r.getStatus()))
                .map(BasketOrderResult::getOrderId)
                .toList();
    }
}

