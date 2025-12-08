package com.tradingbot.dto;

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
public class BasketOrderResponse {

    /**
     * Overall status of basket order placement (SUCCESS, PARTIAL, FAILED)
     */
    private String status;

    /**
     * Overall message
     */
    private String message;

    /**
     * List of individual order results
     */
    @Builder.Default
    private List<BasketOrderResult> orderResults = new ArrayList<>();

    /**
     * Count of successful orders
     */
    private int successCount;

    /**
     * Count of failed orders
     */
    private int failureCount;

    /**
     * Total orders in the basket
     */
    private int totalOrders;

    /**
     * Individual order result within a basket response
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BasketOrderResult {
        private String orderId;
        private String tradingSymbol;
        private String legType;
        private String status;
        private String message;
        private Double executionPrice;
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

