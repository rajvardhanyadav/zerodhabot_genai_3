package com.tradingbot.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DTO for Instrument Information
 */
@Schema(description = "Available instrument with lot size and strike interval details")
public record InstrumentInfo(
    @Schema(description = "Instrument code identifier", example = "NIFTY")
    String code,
    @Schema(description = "Human-readable instrument name", example = "Nifty 50")
    String name,
    @Schema(description = "Lot size for the instrument", example = "50")
    int lotSize,
    @Schema(description = "Strike price interval in points", example = "50.0")
    double strikeInterval
) {
}
