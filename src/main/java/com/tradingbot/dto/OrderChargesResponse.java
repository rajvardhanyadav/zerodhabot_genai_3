package com.tradingbot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderChargesResponse {

    private String transactionType;
    private String tradingsymbol;
    private String exchange;
    private String variety;
    private String product;
    private String orderType;
    private Integer quantity;
    private Double price;
    private Charges charges;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Charges {
        private Double transactionTax;
        private String transactionTaxType;  // "stt"
        private Double exchangeTurnoverCharge;
        private Double sebiTurnoverCharge;
        private Double brokerage;
        private Double stampDuty;
        private GstBreakdown gst;
        private Double total;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class GstBreakdown {
        private Double igst;
        private Double cgst;
        private Double sgst;
        private Double total;
    }
}
