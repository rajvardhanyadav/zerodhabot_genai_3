package com.tradingbot.paper;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import static com.tradingbot.service.TradingConstants.*;

/**
 * Paper Trading Order - In-memory representation of a simulated order
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaperOrder {

    private String orderId;
    private String exchangeOrderId;
    private String placedBy;
    private String variety;
    private String status; // PENDING, OPEN, COMPLETE, CANCELLED, REJECTED
    private String tradingSymbol;
    private String exchange;
    private Long instrumentToken;
    private String transactionType; // BUY or SELL
    private String orderType; // MARKET, LIMIT, SL, SL-M
    private String product; // CNC, MIS, NRML
    private Integer quantity;
    private Double price;
    private Double triggerPrice;
    private Double averagePrice;
    private Integer filledQuantity;
    private Integer pendingQuantity;
    private Integer cancelledQuantity;
    private Integer disclosedQuantity;
    private String validity; // DAY, IOC
    private LocalDateTime orderTimestamp;
    private LocalDateTime exchangeTimestamp;
    private String statusMessage;
    private String parentOrderId;
    private String tag;

    // Additional fields for paper trading
    private Double executionPrice;
    private Double brokerageCharges;
    private Double taxes;
    private Double totalCharges;

    /**
     * Create a new paper order from order request
     */
    public static PaperOrder fromOrderRequest(String orderId, String userId,
                                               String tradingSymbol, String exchange,
                                               String transactionType, Integer quantity,
                                               String product, String orderType, Double price,
                                               Double triggerPrice, String validity,
                                               Integer disclosedQuantity, Long instrumentToken) {
        return PaperOrder.builder()
                .orderId(orderId)
                .exchangeOrderId("PAPER" + orderId)
                .placedBy(userId)
                .variety(VARIETY_REGULAR)
                .status(STATUS_PENDING)
                .tradingSymbol(tradingSymbol)
                .exchange(exchange)
                .instrumentToken(instrumentToken)
                .transactionType(transactionType)
                .orderType(orderType)
                .product(product)
                .quantity(quantity)
                .price(price != null ? price : 0.0)
                .triggerPrice(triggerPrice != null ? triggerPrice : 0.0)
                .averagePrice(0.0)
                .filledQuantity(0)
                .pendingQuantity(quantity)
                .cancelledQuantity(0)
                .disclosedQuantity(disclosedQuantity != null ? disclosedQuantity : 0)
                .validity(validity)
                .orderTimestamp(LocalDateTime.now())
                .statusMessage(MSG_ORDER_PLACED_SUCCESS)
                .brokerageCharges(0.0)
                .taxes(0.0)
                .totalCharges(0.0)
                .build();
    }

    /**
     * Create a deep copy of this order
     */
    public PaperOrder copy() {
        return PaperOrder.builder()
                .orderId(this.orderId)
                .exchangeOrderId(this.exchangeOrderId)
                .placedBy(this.placedBy)
                .variety(this.variety)
                .status(this.status)
                .tradingSymbol(this.tradingSymbol)
                .exchange(this.exchange)
                .instrumentToken(this.instrumentToken)
                .transactionType(this.transactionType)
                .orderType(this.orderType)
                .product(this.product)
                .quantity(this.quantity)
                .price(this.price)
                .triggerPrice(this.triggerPrice)
                .averagePrice(this.averagePrice)
                .filledQuantity(this.filledQuantity)
                .pendingQuantity(this.pendingQuantity)
                .cancelledQuantity(this.cancelledQuantity)
                .disclosedQuantity(this.disclosedQuantity)
                .validity(this.validity)
                .orderTimestamp(this.orderTimestamp)
                .exchangeTimestamp(this.exchangeTimestamp)
                .statusMessage(this.statusMessage)
                .parentOrderId(this.parentOrderId)
                .tag(this.tag)
                .executionPrice(this.executionPrice)
                .brokerageCharges(this.brokerageCharges)
                .taxes(this.taxes)
                .totalCharges(this.totalCharges)
                .build();
    }
}
