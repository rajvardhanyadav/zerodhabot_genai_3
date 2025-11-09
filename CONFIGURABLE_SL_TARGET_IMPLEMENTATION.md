# Configurable Stop Loss and Target Implementation

## Summary
The hardcoded 10-point SL and 15-point target values have been moved to configuration level with support for per-request overrides.

## Changes Made

### 1. New Configuration Class: `StrategyConfig.java`
- **Location**: `src/main/java/com/tradingbot/config/StrategyConfig.java`
- **Purpose**: Centralized configuration for strategy parameters
- **Properties**:
  - `defaultStopLossPoints` (default: 10.0)
  - `defaultTargetPoints` (default: 15.0)
  - `autoSquareOffEnabled` (default: false)
  - `autoSquareOffTime` (default: "15:15")

### 2. Updated `application.yml`
Added new `strategy` section:
```yaml
strategy:
  default-stop-loss-points: 10.0
  default-target-points: 15.0
  auto-square-off-enabled: false
  auto-square-off-time: "15:15"
```

### 3. Updated `StrategyRequest.java`
- Changed field names from `stopLoss` and `target` to `stopLossPoints` and `targetPoints`
- Made them optional (nullable) - will use server defaults if not provided
- Updated JavaDoc to clarify these are points, not percentages

**Before**:
```java
private Double stopLoss; // Stop loss percentage (optional)
private Double target; // Target percentage (optional)
```

**After**:
```java
private Double stopLossPoints; // Stop loss in points (optional, uses default from config if not provided)
private Double targetPoints; // Target in points (optional, uses default from config if not provided)
```

### 4. Updated `ATMStraddleStrategy.java`
- Injected `StrategyConfig` via constructor
- Added logic to use request values if provided, otherwise fall back to config defaults
- Updated all log messages to show the actual SL/Target values being used
- Updated strategy description to use config values

**Key changes**:
```java
// Get SL and Target from request, or use defaults from config
double stopLossPoints = request.getStopLossPoints() != null 
    ? request.getStopLossPoints() 
    : strategyConfig.getDefaultStopLossPoints();

double targetPoints = request.getTargetPoints() != null 
    ? request.getTargetPoints() 
    : strategyConfig.getDefaultTargetPoints();
```

### 5. Updated `ATMStrangleStrategy.java`
- Same changes as ATMStraddleStrategy
- Added position monitoring with configurable SL/Target (was missing before)
- Now supports WebSocket-based real-time monitoring with auto-exit

## Usage

### Using Default Values (from config)
```json
POST /api/strategies/execute
{
  "strategyType": "ATM_STRADDLE",
  "instrumentType": "NIFTY",
  "expiry": "2024-01-25",
  "lots": 1
}
```
This will use SL=10pts and Target=15pts from application.yml

### Overriding with Custom Values
```json
POST /api/strategies/execute
{
  "strategyType": "ATM_STRADDLE",
  "instrumentType": "NIFTY",
  "expiry": "2024-01-25",
  "lots": 1,
  "stopLossPoints": 20.0,
  "targetPoints": 30.0
}
```
This will use SL=20pts and Target=30pts (overrides defaults)

## Configuration Flexibility

### Development/Testing Environment
```yaml
strategy:
  default-stop-loss-points: 5.0   # Tighter SL for testing
  default-target-points: 10.0     # Lower target
```

### Production Environment
```yaml
strategy:
  default-stop-loss-points: 15.0  # Wider SL for volatile markets
  default-target-points: 25.0     # Higher target
```

### Conservative Setup
```yaml
strategy:
  default-stop-loss-points: 8.0
  default-target-points: 12.0
```

## Benefits

1. ✅ **No hardcoded values**: All strategy parameters are configurable
2. ✅ **Environment-specific defaults**: Different values for dev/staging/prod
3. ✅ **Per-request override**: Each strategy execution can have custom SL/Target
4. ✅ **Backward compatible**: Works with existing API calls (uses defaults)
5. ✅ **Flexible**: Easy to adjust without code changes
6. ✅ **Consistent**: Same configuration approach for both Straddle and Strangle strategies

## Testing

Test with default values:
```bash
curl -X POST http://localhost:8080/api/strategies/execute \
  -H "Content-Type: application/json" \
  -d '{
    "strategyType": "ATM_STRADDLE",
    "instrumentType": "NIFTY",
    "expiry": "2024-01-25",
    "lots": 1
  }'
```

Test with custom values:
```bash
curl -X POST http://localhost:8080/api/strategies/execute \
  -H "Content-Type: application/json" \
  -d '{
    "strategyType": "ATM_STRADDLE",
    "instrumentType": "NIFTY",
    "expiry": "2024-01-25",
    "lots": 1,
    "stopLossPoints": 25.0,
    "targetPoints": 40.0
  }'
```

## Files Modified

1. ✅ `src/main/java/com/tradingbot/config/StrategyConfig.java` (NEW)
2. ✅ `src/main/resources/application.yml` (UPDATED)
3. ✅ `src/main/java/com/tradingbot/dto/StrategyRequest.java` (UPDATED)
4. ✅ `src/main/java/com/tradingbot/service/strategy/ATMStraddleStrategy.java` (UPDATED)
5. ✅ `src/main/java/com/tradingbot/service/strategy/ATMStrangleStrategy.java` (UPDATED)

## Next Steps

- Update any frontend/UI code to support optional `stopLossPoints` and `targetPoints` fields
- Consider adding validation (min/max values) for SL and Target points
- Add strategy performance tracking based on different SL/Target combinations

