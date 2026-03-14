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
@Schema(description = "Request to place or modify an order")
public class OrderRequest {
    @Schema(description = "Trading symbol of the instrument", example = "NIFTY2430621000CE")
    private String tradingSymbol;

    @Schema(description = "Exchange segment", example = "NFO")
    private String exchange;

    @Schema(description = "Transaction type: BUY or SELL", example = "BUY")
    private String transactionType;

    @Schema(description = "Order quantity (number of shares/lots)", example = "50")
    private Integer quantity;

    @Schema(description = "Product type: CNC (delivery), MIS (intraday), NRML (F&O normal)", example = "NRML")
    private String product;

    @Schema(description = "Order type: MARKET, LIMIT, SL, SL-M", example = "MARKET")
    private String orderType;

    @Schema(description = "Limit price (required for LIMIT and SL orders)", example = "150.50")
    private Double price;

    @Schema(description = "Trigger price (required for SL and SL-M orders)", example = "148.00")
    private Double triggerPrice;

    @Schema(description = "Order validity: DAY or IOC", example = "DAY")
    private String validity;

    @Schema(description = "Quantity to disclose publicly to the market", example = "0")
    private Integer disclosedQuantity;
}