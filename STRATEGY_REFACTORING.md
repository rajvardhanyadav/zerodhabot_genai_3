# Trading Strategy Architecture - Refactoring Documentation

## Overview

The strategy execution system has been refactored from a monolithic `StrategyService` class into a modular, extensible architecture that makes it easy to add new trading strategies.

## New Architecture

### 1. **TradingStrategy Interface** (`TradingStrategy.java`)
- Base interface that all trading strategies must implement
- Defines the contract for strategy execution
- Methods:
  - `execute()`: Execute the strategy and return results
  - `getStrategyName()`: Get the strategy name
  - `getStrategyDescription()`: Get strategy description

### 2. **BaseStrategy Abstract Class** (`BaseStrategy.java`)
- Provides common utility methods for all strategies
- Reduces code duplication
- Shared functionality includes:
  - `getCurrentSpotPrice()`: Fetch current market price
  - `getATMStrike()`: Calculate ATM strike price
  - `getLotSize()`: Get lot size (with caching)
  - `getOptionInstruments()`: Fetch option contracts
  - `findOptionInstrument()`: Find specific option by strike and type
  - `createOrderRequest()`: Create order request objects
  - `getOrderPrice()`: Retrieve order execution price
  - Expiry matching logic (weekly, monthly, specific dates)

### 3. **Individual Strategy Classes**

#### **ATMStraddleStrategy** (`ATMStraddleStrategy.java`)
- Implements the ATM Straddle strategy
- Buys 1 ATM Call + 1 ATM Put
- Non-directional strategy for high volatility
- Clean, focused implementation with proper error handling

#### **ATMStrangleStrategy** (`ATMStrangleStrategy.java`)
- Implements the ATM Strangle strategy
- Buys 1 OTM Call + 1 OTM Put
- Lower cost alternative to straddle
- Configurable strike gap

### 4. **StrategyFactory** (`StrategyFactory.java`)
- Manages strategy instances
- Provides strategy selection based on strategy type
- Uses Spring dependency injection
- Easy to extend with new strategies

### 5. **Refactored StrategyService** (`StrategyService.java`)
- Now acts as a coordinator
- Delegates strategy execution to specific implementations
- Manages active strategy tracking
- Provides instrument and expiry information

## Benefits of New Architecture

### ✅ **Separation of Concerns**
- Each strategy is in its own file
- Clear responsibilities for each class
- Easier to understand and maintain

### ✅ **Easy to Extend**
- Adding a new strategy is simple:
  1. Create a new class extending `BaseStrategy`
  2. Implement the `execute()` method
  3. Register in `StrategyFactory`
  
### ✅ **Code Reusability**
- Common logic is in `BaseStrategy`
- No duplication across strategies
- DRY principle maintained

### ✅ **Testability**
- Each strategy can be tested independently
- Mock dependencies easily
- Better unit test coverage

### ✅ **Maintainability**
- Changes to one strategy don't affect others
- Easier debugging
- Clear code organization

## How to Add a New Strategy

### Step 1: Create Strategy Class
```java
@Slf4j
@Component
public class BullCallSpreadStrategy extends BaseStrategy {

    public BullCallSpreadStrategy(TradingService tradingService, 
                                   Map<String, Integer> lotSizeCache) {
        super(tradingService, lotSizeCache);
    }

    @Override
    public StrategyExecutionResponse execute(StrategyRequest request, 
                                              String executionId)
            throws KiteException, IOException {
        
        log.info("Executing Bull Call Spread for {}", 
                 request.getInstrumentType());
        
        // Your strategy logic here
        // Use helper methods from BaseStrategy
        
        return response;
    }

    @Override
    public String getStrategyName() {
        return "Bull Call Spread";
    }

    @Override
    public String getStrategyDescription() {
        return "Bullish strategy using call options";
    }
}
```

### Step 2: Register in StrategyFactory
```java
@Component
@RequiredArgsConstructor
@Slf4j
public class StrategyFactory {

    private final ATMStraddleStrategy atmStraddleStrategy;
    private final ATMStrangleStrategy atmStrangleStrategy;
    private final BullCallSpreadStrategy bullCallSpreadStrategy; // Add here

    public TradingStrategy getStrategy(StrategyType strategyType) {
        return switch (strategyType) {
            case ATM_STRADDLE -> atmStraddleStrategy;
            case ATM_STRANGLE -> atmStrangleStrategy;
            case BULL_CALL_SPREAD -> bullCallSpreadStrategy; // Add here
            default -> throw new IllegalArgumentException(
                "Strategy not implemented: " + strategyType);
        };
    }
    
    // Update isStrategyImplemented() method
}
```

### Step 3: That's it!
The new strategy is automatically available through the API.

## File Structure

```
src/main/java/com/tradingbot/
├── service/
│   ├── StrategyService.java           # Main service (coordinator)
│   ├── TradingService.java            # Trading operations
│   └── strategy/                      # Strategy package
│       ├── TradingStrategy.java       # Interface
│       ├── BaseStrategy.java          # Base class
│       ├── StrategyFactory.java       # Factory
│       ├── ATMStraddleStrategy.java   # Straddle implementation
│       ├── ATMStrangleStrategy.java   # Strangle implementation
│       └── [Future strategies...]     # Easy to add more
```

## Key Features Retained

✅ **Lot Size Caching**: Still fetches from Kite API and caches for session  
✅ **Order Validation**: Proper error handling for failed orders  
✅ **Instrument Discovery**: Dynamic fetching from Kite API  
✅ **Expiry Management**: Weekly, monthly, and custom date support  
✅ **Logging**: Comprehensive logging at all levels  

## Migration Notes

- **No API Changes**: The REST API endpoints remain unchanged
- **No DTO Changes**: Request/Response structures are the same
- **Backward Compatible**: Existing functionality is preserved
- **Same Behavior**: All strategies work exactly as before

## Future Enhancements

This architecture makes it easy to add:
- Bull Call Spread
- Bear Put Spread
- Iron Condor
- Butterfly Spreads
- Custom strategy configurations
- Strategy backtesting
- Strategy optimization
- Risk management layers

## Summary

The refactoring transforms a monolithic 500+ line service class into a clean, modular architecture where:
- Each strategy is ~150 lines in its own file
- Common code is shared via inheritance
- Adding new strategies takes minutes, not hours
- Code is easier to read, test, and maintain

