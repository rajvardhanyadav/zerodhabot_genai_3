package com.tradingbot.dto;

/**
 * DTO for Strategy Type Information
 */
public record StrategyTypeInfo(
    String name,
    String description,
    boolean implemented
) {
}

