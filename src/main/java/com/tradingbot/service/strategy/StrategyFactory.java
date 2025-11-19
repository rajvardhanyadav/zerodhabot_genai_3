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
    private final ATMStrangleStrategy atmStrangleStrategy;
    private final ScalpingAtmOptionStrategy scalpingAtmOptionStrategy;
    private final SellATMStraddleStrategy sellAtmStraddleStrategy;
    // Add more strategies here as they are implemented

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
            case ATM_STRANGLE -> atmStrangleStrategy;
            case INTRADAY_SCALPING_ATM -> scalpingAtmOptionStrategy;
            case SELL_ATM_STRADDLE -> sellAtmStraddleStrategy;
            // Add more strategies here as they are implemented
            // case BULL_CALL_SPREAD -> bullCallSpreadStrategy;
            // case BEAR_PUT_SPREAD -> bearPutSpreadStrategy;
            // case IRON_CONDOR -> ironCondorStrategy;
            default -> throw new IllegalArgumentException("Strategy not implemented: " + strategyType);
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
               strategyType == StrategyType.ATM_STRANGLE ||
               strategyType == StrategyType.INTRADAY_SCALPING_ATM ||
               strategyType == StrategyType.SELL_ATM_STRADDLE;
        // Add more as implemented
    }

    /**
     * Get all available strategies with their metadata
     *
     * @return Map of strategy type to strategy implementation
     */
    public Map<StrategyType, TradingStrategy> getAllStrategies() {
        Map<StrategyType, TradingStrategy> strategies = new HashMap<>();
        strategies.put(StrategyType.ATM_STRADDLE, atmStraddleStrategy);
        strategies.put(StrategyType.ATM_STRANGLE, atmStrangleStrategy);
        strategies.put(StrategyType.INTRADAY_SCALPING_ATM, scalpingAtmOptionStrategy);
        strategies.put(StrategyType.SELL_ATM_STRADDLE, sellAtmStraddleStrategy);
        // Add more as implemented
        return strategies;
    }
}
