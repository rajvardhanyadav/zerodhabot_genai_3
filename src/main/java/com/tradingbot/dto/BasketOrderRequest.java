package com.tradingbot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Request to place multiple orders as a single basket")
public class BasketOrderRequest {

    @Schema(description = "List of individual order items in the basket")
    private List<BasketOrderItem> orders;

    @Schema(description = "Optional tag to identify this basket for tracking", example = "straddle-entry")
    private String tag;

    /**
     * Individual order item within a basket
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "Individual order item within a basket order")
    public static class BasketOrderItem {
        @Schema(description = "Trading symbol of the instrument", example = "NIFTY2430621000CE")
        private String tradingSymbol;

        @Schema(description = "Exchange segment", example = "NFO")
        private String exchange;

        @Schema(description = "Transaction type: BUY or SELL", example = "SELL")
        private String transactionType;

        @Schema(description = "Order quantity", example = "50")
        private Integer quantity;

        @Schema(description = "Product type: CNC, MIS, NRML", example = "NRML")
        private String product;

        @Schema(description = "Order type: MARKET, LIMIT, SL, SL-M", example = "MARKET")
        private String orderType;

        @Schema(description = "Limit price (required for LIMIT and SL orders)", example = "150.50")
        private Double price;

        @Schema(description = "Trigger price (required for SL and SL-M orders)", example = "148.00")
        private Double triggerPrice;

        @Schema(description = "Order validity: DAY or IOC", example = "DAY")
        private String validity;

        @Schema(description = "Quantity to disclose publicly", example = "0")
        private Integer disclosedQuantity;

        @Schema(description = "Leg identifier (e.g., CALL, PUT)", example = "CALL")
        private String legType;

        @Schema(description = "Instrument token for WebSocket monitoring", example = "12345678")
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
