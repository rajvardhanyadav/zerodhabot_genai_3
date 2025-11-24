package com.tradingbot.paper.entity;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class OrderCharges {
    private BigDecimal brokerage;
    private BigDecimal stt;
    private BigDecimal exchangeTxnCharge;
    private BigDecimal gst;
    private BigDecimal sebiCharge;
    private BigDecimal stampDuty;
    private BigDecimal totalCharges;
    private BigDecimal netTurnover; // Total amount required (Buy) or received (Sell)
}
