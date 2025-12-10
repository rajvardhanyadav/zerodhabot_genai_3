package com.tradingbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for persistence layer.
 */
@Configuration
@ConfigurationProperties(prefix = "persistence")
@Data
public class PersistenceConfig {

    /**
     * Enable/disable persistence globally
     */
    private boolean enabled = true;

    /**
     * Data retention settings
     */
    private RetentionConfig retention = new RetentionConfig();

    /**
     * Cleanup job settings
     */
    private CleanupConfig cleanup = new CleanupConfig();

    @Data
    public static class RetentionConfig {
        /**
         * Number of days to retain trade records
         */
        private int tradesDays = 365;

        /**
         * Number of days to retain delta/Greeks snapshots
         */
        private int deltaSnapshotsDays = 90;

        /**
         * Number of days to retain position snapshots
         */
        private int positionSnapshotsDays = 180;

        /**
         * Number of days to retain order timing metrics
         */
        private int orderTimingDays = 90;

        /**
         * Number of days to retain alert history
         */
        private int alertsDays = 90;

        /**
         * Number of days to retain MTM snapshots
         */
        private int mtmSnapshotsDays = 30;

        /**
         * Number of days to retain strategy config history
         */
        private int strategyConfigDays = 365;

        /**
         * Number of days to retain WebSocket events
         */
        private int websocketEventsDays = 30;

        /**
         * Number of days to retain system health snapshots
         */
        private int systemHealthDays = 7;
    }

    @Data
    public static class CleanupConfig {
        /**
         * Enable/disable automatic cleanup
         */
        private boolean enabled = true;

        /**
         * Cron expression for cleanup job
         */
        private String cron = "0 0 2 * * ?";
    }
}

