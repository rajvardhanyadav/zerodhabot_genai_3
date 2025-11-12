package com.tradingbot.paper;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Paper Trading Position
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaperPosition {

    private String tradingSymbol;
    private String exchange;
    private Long instrumentToken;
    private String product;

    // Quantity details
    @Builder.Default
    private Integer quantity = 0;
    @Builder.Default
    private Integer overnightQuantity = 0;
    @Builder.Default
    private Integer multiplier = 1;

    // Buy details
    @Builder.Default
    private Double averagePrice = 0.0;
    @Builder.Default
    private Integer buyQuantity = 0;
    @Builder.Default
    private Double buyPrice = 0.0;
    @Builder.Default
    private Double buyValue = 0.0;
    @Builder.Default
    private Double buyM2M = 0.0;

    // Sell details
    @Builder.Default
    private Integer sellQuantity = 0;
    @Builder.Default
    private Double sellPrice = 0.0;
    @Builder.Default
    private Double sellValue = 0.0;
    @Builder.Default
    private Double sellM2M = 0.0;

    // Day trading details
    @Builder.Default
    private Integer dayBuyQuantity = 0;
    @Builder.Default
    private Double dayBuyPrice = 0.0;
    @Builder.Default
    private Double dayBuyValue = 0.0;
    @Builder.Default
    private Integer daySellQuantity = 0;
    @Builder.Default
    private Double daySellPrice = 0.0;
    @Builder.Default
    private Double daySellValue = 0.0;

    // P&L details
    @Builder.Default
    private Double pnl = 0.0;
    @Builder.Default
    private Double realised = 0.0;
    @Builder.Default
    private Double unrealised = 0.0;
    @Builder.Default
    private Double m2m = 0.0;

    // Current market price
    @Builder.Default
    private Double lastPrice = 0.0;
    @Builder.Default
    private Double closePrice = 0.0;
    @Builder.Default
    private Double value = 0.0;

    private LocalDateTime lastUpdated;
}
