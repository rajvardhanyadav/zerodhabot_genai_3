package com.tradingbot.dto;

/**
 * DTO for Instrument Information
 */
public record InstrumentInfo(
    String code,
    String name,
    int lotSize,
    double strikeInterval
) {
}
