package com.tradingbot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Current status of the trading bot lifecycle")
public class BotStatusResponse {
    @Schema(description = "Bot status: RUNNING or STOPPED", example = "RUNNING")
    private String status;

    @Schema(description = "Timestamp of the last status change", example = "2026-03-14T10:30:00Z")
    private Instant lastUpdated;
}

