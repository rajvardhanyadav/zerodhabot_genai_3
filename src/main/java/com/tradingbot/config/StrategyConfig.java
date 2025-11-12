package com.tradingbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Strategy Configuration
 * Default values for strategy parameters like stop loss and target
 */
@Configuration
@ConfigurationProperties(prefix = "strategy")
@Data
public class StrategyConfig {

    // Default strategy parameters (can be overridden per execution)
    private double defaultStopLossPoints = 10.0;
    private double defaultTargetPoints = 15.0;

    // Auto square-off configuration
    private boolean autoSquareOffEnabled = false;
    private String autoSquareOffTime = "15:15"; // HH:mm format
}
