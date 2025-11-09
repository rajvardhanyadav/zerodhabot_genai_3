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

    /**
     * Default stop loss in points (not percentage)
     * Can be overridden per strategy execution via API request
     */
    private double defaultStopLossPoints = 10.0;

    /**
     * Default target in points (not percentage)
     * Can be overridden per strategy execution via API request
     */
    private double defaultTargetPoints = 15.0;

    /**
     * Enable/disable auto square off at end of day
     */
    private boolean autoSquareOffEnabled = false;

    /**
     * Auto square off time (HH:mm format)
     */
    private String autoSquareOffTime = "15:15";
}

