package com.tradingbot.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderRequest {
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
}