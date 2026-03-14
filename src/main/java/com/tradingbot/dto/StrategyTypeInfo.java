package com.tradingbot.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DTO for Strategy Type Information
 */
@Schema(description = "Available strategy type with implementation status")
public record StrategyTypeInfo(
    @Schema(description = "Strategy type identifier", example = "SELL_ATM_STRADDLE")
    String name,
    @Schema(description = "Human-readable description of the strategy", example = "Sell 1 ATM Call + Sell 1 ATM Put")
    String description,
    @Schema(description = "Whether this strategy is currently implemented and available for execution", example = "true")
    boolean implemented
) {
}

