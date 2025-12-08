package com.tradingbot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request DTO for placing basket orders (multiple orders at once)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BasketOrderRequest {

    /**
     * List of individual order items in the basket
     */
    private List<BasketOrderItem> orders;

    /**
     * Optional tag to identify this basket (for tracking purposes)
     */
    private String tag;

    /**
     * Individual order item within a basket
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BasketOrderItem {
        private String tradingSymbol;
        private String exchange;
        private String transactionType; // BUY or SELL
        private Integer quantity;
        private String product; // CNC, MIS, NRML
        private String orderType; // MARKET, LIMIT, SL, SL-M
        private Double price;
        private Double triggerPrice;
        private String validity; // DAY, IOC
        private Integer disclosedQuantity;

        /**
         * Optional leg identifier (e.g., "CALL", "PUT")
         */
        private String legType;

        /**
         * Instrument token for WebSocket monitoring
         */
        private Long instrumentToken;

        /**
         * Convert to OrderRequest for individual order placement
         */
        public OrderRequest toOrderRequest() {
            return OrderRequest.builder()
                    .tradingSymbol(tradingSymbol)
                    .exchange(exchange)
                    .transactionType(transactionType)
                    .quantity(quantity)
                    .product(product)
                    .orderType(orderType)
                    .price(price)
                    .triggerPrice(triggerPrice)
                    .validity(validity)
                    .disclosedQuantity(disclosedQuantity)
                    .build();
        }
    }
}
