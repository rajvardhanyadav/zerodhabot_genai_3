package com.tradingbot.paper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Paper Trading Order - In-memory representation of a simulated order
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
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
        PaperOrder order = new PaperOrder();
        order.setOrderId(orderId);
        order.setExchangeOrderId("PAPER" + orderId);
        order.setPlacedBy(userId);
        order.setVariety("regular");
        order.setStatus("PENDING");
        order.setTradingSymbol(tradingSymbol);
        order.setExchange(exchange);
        order.setInstrumentToken(instrumentToken);
        order.setTransactionType(transactionType);
        order.setOrderType(orderType);
        order.setProduct(product);
        order.setQuantity(quantity);
        order.setPrice(price != null ? price : 0.0);
        order.setTriggerPrice(triggerPrice != null ? triggerPrice : 0.0);
        order.setAveragePrice(0.0);
        order.setFilledQuantity(0);
        order.setPendingQuantity(quantity);
        order.setCancelledQuantity(0);
        order.setDisclosedQuantity(disclosedQuantity != null ? disclosedQuantity : 0);
        order.setValidity(validity);
        order.setOrderTimestamp(LocalDateTime.now());
        order.setStatusMessage("Order placed");
        order.setBrokerageCharges(0.0);
        order.setTaxes(0.0);
        order.setTotalCharges(0.0);
        return order;
    }
}

