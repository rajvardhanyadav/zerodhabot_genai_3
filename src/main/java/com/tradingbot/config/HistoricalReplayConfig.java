package com.tradingbot.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for historical replay behavior.
 */
@Configuration
@ConfigurationProperties(prefix = "historical.replay")
@Getter
@Setter
public class HistoricalReplayConfig {
    /**
     * Sleep duration (in milliseconds) between each simulated second during historical replay.
     * Set to 0 for fastest possible replay.
     */
    private long sleepMillisPerSecond = 2L;
}
