package com.tradingbot.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for Day P&L
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DayPnLResponse {

    /**
     * Total realized P&L for the day (from closed positions)
     */
    private Double totalRealised;

    /**
     * Total unrealized P&L for the day (from open positions)
     */
    private Double totalUnrealised;

    /**
     * Total M2M (Mark to Market) P&L
     */
    private Double totalM2M;

    /**
     * Total day P&L (realized + unrealized)
     */
    private Double totalDayPnL;

    /**
     * Number of positions
     */
    private Integer positionCount;

    /**
     * Trading mode (PAPER or LIVE)
     */
    private String tradingMode;
}
