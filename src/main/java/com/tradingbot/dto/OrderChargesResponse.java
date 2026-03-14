package com.tradingbot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Detailed charge breakdown for an executed order")
public class OrderChargesResponse {

    @Schema(description = "Transaction type: BUY or SELL", example = "SELL")
    private String transactionType;

    @Schema(description = "Trading symbol of the instrument", example = "NIFTY2430621000CE")
    private String tradingsymbol;

    @Schema(description = "Exchange segment", example = "NFO")
    private String exchange;

    @Schema(description = "Order variety: regular, amo, iceberg, auction", example = "regular")
    private String variety;

    @Schema(description = "Product type: CNC, MIS, NRML", example = "NRML")
    private String product;

    @Schema(description = "Order type: MARKET, LIMIT, SL, SL-M", example = "MARKET")
    private String orderType;

    @Schema(description = "Executed quantity", example = "50")
    private Integer quantity;

    @Schema(description = "Execution price per unit (INR)", example = "162.75")
    private Double price;

    @Schema(description = "Detailed charge breakdown")
    private Charges charges;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "Breakdown of all charges and taxes for an order")
    public static class Charges {
        @Schema(description = "Securities Transaction Tax (INR)", example = "4.06")
        private Double transactionTax;

        @Schema(description = "Type of transaction tax (e.g., stt)", example = "stt")
        private String transactionTaxType;

        @Schema(description = "Exchange turnover charge (INR)", example = "2.64")
        private Double exchangeTurnoverCharge;

        @Schema(description = "SEBI turnover charge (INR)", example = "0.08")
        private Double sebiTurnoverCharge;

        @Schema(description = "Brokerage charged (INR)", example = "20.00")
        private Double brokerage;

        @Schema(description = "Stamp duty (INR)", example = "0.49")
        private Double stampDuty;

        @Schema(description = "GST breakdown")
        private GstBreakdown gst;

        @Schema(description = "Total charges (INR)", example = "31.35")
        private Double total;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "GST breakdown into IGST, CGST, and SGST components")
    public static class GstBreakdown {
        @Schema(description = "Integrated GST (INR)", example = "4.08")
        private Double igst;

        @Schema(description = "Central GST (INR)", example = "0.0")
        private Double cgst;

        @Schema(description = "State GST (INR)", example = "0.0")
        private Double sgst;

        @Schema(description = "Total GST (INR)", example = "4.08")
        private Double total;
    }
}
