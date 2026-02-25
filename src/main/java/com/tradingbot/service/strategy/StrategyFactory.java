package com.tradingbot.service.strategy;

import com.tradingbot.model.StrategyType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Factory class to manage and provide strategy implementations
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StrategyFactory {

    private final ATMStraddleStrategy atmStraddleStrategy;
    private final SellATMStraddleStrategy sellAtmStraddleStrategy;
    private final ShortStrangleStrategy shortStrangleStrategy;

    /**
     * Get strategy implementation based on strategy type
     *
     * @param strategyType Type of strategy to execute
     * @return Strategy implementation
     * @throws IllegalArgumentException if strategy is not implemented
     */
    public TradingStrategy getStrategy(StrategyType strategyType) {
        log.debug("Getting strategy for type: {}", strategyType);

        return switch (strategyType) {
            case ATM_STRADDLE -> atmStraddleStrategy;
            case SELL_ATM_STRADDLE -> sellAtmStraddleStrategy;
            case SHORT_STRANGLE -> shortStrangleStrategy;
        };
    }

    /**
     * Check if a strategy is implemented
     *
     * @param strategyType Type of strategy to check
     * @return true if implemented, false otherwise
     */
    public boolean isStrategyImplemented(StrategyType strategyType) {
        return strategyType == StrategyType.ATM_STRADDLE ||
               strategyType == StrategyType.SELL_ATM_STRADDLE ||
               strategyType == StrategyType.SHORT_STRANGLE;
    }

    /**
     * Get all available strategies with their metadata
     *
     * @return Map of strategy type to strategy implementation
     */
    public Map<StrategyType, TradingStrategy> getAllStrategies() {
        Map<StrategyType, TradingStrategy> strategies = new HashMap<>();
        strategies.put(StrategyType.ATM_STRADDLE, atmStraddleStrategy);
        strategies.put(StrategyType.SELL_ATM_STRADDLE, sellAtmStraddleStrategy);
        strategies.put(StrategyType.SHORT_STRANGLE, shortStrangleStrategy);
        return strategies;
    }
}
