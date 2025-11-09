package com.tradingbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Paper Trading Configuration
 * Controls whether the application runs in paper trading mode or live trading mode
 */
@Configuration
@ConfigurationProperties(prefix = "trading")
@Data
public class PaperTradingConfig {

    /**
     * Master flag to enable/disable paper trading mode
     * true = Paper Trading (simulated orders)
     * false = Live Trading (real orders via Kite API)
     */
    private boolean paperTradingEnabled = true;

    /**
     * Initial virtual balance for paper trading account
     */
    private double initialBalance = 1000000.0; // 10 Lakhs

    /**
     * Enable/disable brokerage charges in paper trading
     */
    private boolean applyBrokerageCharges = true;

    /**
     * Brokerage per order (flat fee)
     */
    private double brokeragePerOrder = 20.0;

    /**
     * STT (Securities Transaction Tax) percentage
     */
    private double sttPercentage = 0.025; // 0.025%

    /**
     * Transaction charges percentage
     */
    private double transactionCharges = 0.00325; // 0.00325%

    /**
     * GST on brokerage percentage
     */
    private double gstPercentage = 18.0; // 18%

    /**
     * SEBI charges percentage
     */
    private double sebiCharges = 0.0001; // 0.0001%

    /**
     * Stamp duty percentage
     */
    private double stampDuty = 0.003; // 0.003%

    /**
     * Slippage percentage for market orders in paper trading
     */
    private double slippagePercentage = 0.05; // 0.05%

    /**
     * Enable/disable realistic order execution delays
     */
    private boolean enableExecutionDelay = true;

    /**
     * Order execution delay in milliseconds
     */
    private long executionDelayMs = 500; // 500ms

    /**
     * Enable/disable order rejection simulation
     */
    private boolean enableOrderRejection = false;

    /**
     * Order rejection probability (0.0 to 1.0)
     */
    private double rejectionProbability = 0.02; // 2%
}

