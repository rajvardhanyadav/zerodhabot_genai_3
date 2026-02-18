package com.tradingbot.backtest.strategy;

import com.tradingbot.backtest.dto.BacktestRequest;
import com.tradingbot.backtest.dto.CandleData;
import com.tradingbot.backtest.engine.BacktestContext;
import com.tradingbot.model.StrategyType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Factory for creating backtest strategy instances.
 *
 * This factory is COMPLETELY SEPARATE from the live trading StrategyFactory
 * to ensure isolation between backtesting and live trading logic.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BacktestStrategyFactory {

    private final Map<StrategyType, BacktestStrategy> strategyRegistry = new HashMap<>();

    /**
     * Register a backtest strategy implementation.
     */
    public void registerStrategy(StrategyType type, BacktestStrategy strategy) {
        strategyRegistry.put(type, strategy);
        log.info("Registered backtest strategy: {} -> {}", type, strategy.getStrategyName());
    }

    /**
     * Get a backtest strategy for the given type.
     *
     * @param type The strategy type
     * @return BacktestStrategy implementation
     * @throws IllegalArgumentException if strategy type is not supported
     */
    public BacktestStrategy getStrategy(StrategyType type) {
        BacktestStrategy strategy = strategyRegistry.get(type);
        if (strategy == null) {
            throw new IllegalArgumentException("Unsupported backtest strategy type: " + type);
        }
        return strategy;
    }

    /**
     * Check if a strategy type is supported for backtesting.
     */
    public boolean isSupported(StrategyType type) {
        return strategyRegistry.containsKey(type);
    }

    /**
     * Get all supported strategy types.
     */
    public java.util.Set<StrategyType> getSupportedTypes() {
        return strategyRegistry.keySet();
    }
}

