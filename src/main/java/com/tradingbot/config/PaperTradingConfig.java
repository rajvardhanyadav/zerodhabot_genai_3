package com.tradingbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Paper Trading Configuration
 * Controls whether the application runs in paper trading mode or live trading mode
 */
@Configuration
@ConfigurationProperties(prefix = "trading")
@Data
public class PaperTradingConfig {

    // Master flag
    private boolean paperTradingEnabled = true;
    private double initialBalance = 1000000.0; // 10 Lakhs

    // Charges configuration
    private boolean applyBrokerageCharges = true;
    private double brokeragePerOrder = 20.0;
    private double sttPercentage = 0.02; // 0.025%
    private double transactionCharges = 0.00325; // 0.00325%
    private double gstPercentage = 18.0; // 18%
    private double sebiCharges = 0.0001; // 0.0001%
    private double stampDuty = 0.003; // 0.003%

    // Execution configuration
    private double slippagePercentage = 0.05; // 0.05%
    private boolean enableExecutionDelay = true;
    private long executionDelayMs = 500; // 500ms

    // Order rejection simulation
    private boolean enableOrderRejection = false;
    private double rejectionProbability = 0.02; // 2%

    /**
     * Runtime override for paper trading mode. When set, overrides
     * the static paperTradingEnabled config value. Thread-safe for runtime toggling.
     */
    private final AtomicBoolean runtimeOverride = new AtomicBoolean();
    private volatile boolean runtimeOverrideSet = false;

    /**
     * Thread-safe runtime toggle. Overrides the static config value.
     * Called by PaperTradingController.setTradingMode().
     */
    public void setRuntimePaperTradingEnabled(boolean enabled) {
        runtimeOverride.set(enabled);
        runtimeOverrideSet = true;
    }

    /**
     * Returns effective paper trading state: runtime override if set, else static config.
     */
    public boolean isEffectivelyPaperTradingEnabled() {
        return runtimeOverrideSet ? runtimeOverride.get() : paperTradingEnabled;
    }
}
