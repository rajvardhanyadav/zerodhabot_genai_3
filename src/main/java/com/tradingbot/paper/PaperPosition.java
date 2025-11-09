package com.tradingbot.paper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Paper Trading Position
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaperPosition {

    private String tradingSymbol;
    private String exchange;
    private Long instrumentToken;
    private String product;

    // Quantity details
    private Integer quantity;
    private Integer overnightQuantity;
    private Integer multiplier;

    // Buy details
    private Double averagePrice;
    private Integer buyQuantity;
    private Double buyPrice;
    private Double buyValue;
    private Double buyM2M;

    // Sell details
    private Integer sellQuantity;
    private Double sellPrice;
    private Double sellValue;
    private Double sellM2M;

    // Day trading details
    private Integer dayBuyQuantity;
    private Double dayBuyPrice;
    private Double dayBuyValue;
    private Integer daySellQuantity;
    private Double daySellPrice;
    private Double daySellValue;

    // P&L details
    private Double pnl;
    private Double realised;
    private Double unrealised;
    private Double m2m;

    // Current market price
    private Double lastPrice;
    private Double closePrice;
    private Double value;

    private LocalDateTime lastUpdated;
}

