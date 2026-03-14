package com.tradingbot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for Day P&L
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Day profit and loss summary across all positions")
public class DayPnLResponse {

    @Schema(description = "Total realized P&L for the day (from closed positions)", example = "1250.75")
    private Double totalRealised;

    @Schema(description = "Total unrealized P&L for the day (from open positions)", example = "-320.50")
    private Double totalUnrealised;

    @Schema(description = "Total Mark-to-Market P&L", example = "930.25")
    private Double totalM2M;

    @Schema(description = "Total day P&L (realized + unrealized)", example = "930.25")
    private Double totalDayPnL;

    @Schema(description = "Number of open/closed positions", example = "4")
    private Integer positionCount;

    @Schema(description = "Trading mode: PAPER or LIVE", example = "PAPER")
    private String tradingMode;
}
