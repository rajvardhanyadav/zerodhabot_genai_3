# Individual Leg Exit System Implementation

## Overview
Implemented a sophisticated individual leg exit system for the ATM Straddle Strategy that can handle both individual leg closures and full position exits based on different price thresholds.

## Key Changes

### 1. PositionMonitor.java
Added a new callback system to support both individual and full exits:

**New Field:**
- `BiConsumer<String, String> individualLegExitCallback` - Callback for individual leg exits (legSymbol, reason)

**New Method:**
- `updatePriceWithDifferenceCheck(ArrayList<Tick> ticks)` - Monitors price differences and triggers exits based on:
  - **Individual leg exit**: If price difference ≤ -2 points (loss)
  - **All legs exit**: If price difference ≥ 4 points (profit)

**Updated Method:**
- `triggerIndividualLegExit(String legSymbol, double priceDifference)` - Now uses `individualLegExitCallback` if set, preventing accidental full exits

### 2. ATMStraddleStrategy.java

**New Method:**
- `exitIndividualLeg()` - Handles closing a single leg without affecting other legs
  - Exits only the specific leg that hit the threshold
  - Monitors remaining legs
  - Automatically stops monitoring when all legs are closed individually

**Updated Method:**
- `createPositionMonitor()` - Now sets both callbacks:
  - `exitCallback` - For full exits (stop loss, target, P&L diff, 4+ profit)
  - `individualLegExitCallback` - For individual leg exits (-2 or worse loss)

## Exit Logic Flow

### Individual Leg Exit (Price ≤ -2 points)
```
1. Price drops by 2+ points on one leg
2. PositionMonitor.updatePriceWithDifferenceCheck() detects threshold
3. Calls triggerIndividualLegExit()
4. Calls individualLegExitCallback (NOT exitCallback)
5. ATMStraddleStrategy.exitIndividualLeg() is invoked
6. Only that specific leg is sold
7. Leg is removed from monitoring
8. Other legs continue to be monitored
9. If all legs closed individually, monitoring stops
```

### Full Exit (Price ≥ 4 points or other thresholds)
```
1. Any leg gains 4+ points OR stop loss/target hit
2. PositionMonitor calls triggerExit()
3. Calls exitCallback
4. ATMStraddleStrategy.exitAllLegs() is invoked
5. Both Call and Put legs are sold
6. Monitoring stops
7. Strategy completion callback is triggered
```

## Problem Solved

**Previous Issue:**
- `triggerIndividualLegExit()` was calling `exitCallback`, which invoked `exitAllLegs()`
- This would close BOTH legs even when only one leg hit the individual threshold

**Solution:**
- Introduced separate callback system (`individualLegExitCallback`)
- Individual leg exits now call their own dedicated callback
- `exitIndividualLeg()` only closes the specific leg symbol passed to it
- Proper isolation between individual and full exit logic

## Usage Example

To use the price difference monitoring, call this method in WebSocketService:

```java
monitor.updatePriceWithDifferenceCheck(ticks);
```

This will:
- Monitor each leg's price difference from entry
- Close individual legs at -2 points loss
- Close all legs at +4 points profit

## Key Features

1. **Separation of Concerns**: Individual and full exits are completely separate
2. **Automatic Cleanup**: When all legs are closed individually, monitoring stops automatically
3. **Proper Logging**: Different log messages for individual vs. full exits
4. **Callback Flexibility**: Strategy can handle both exit types independently
5. **Monitor State Management**: Legs are removed from monitoring after individual exit

## Testing Scenarios

1. **Single leg hits -2 points**: Only that leg closes, other continues monitoring
2. **Any leg hits +4 points**: Both legs close immediately
3. **Both legs hit -2 individually**: Each closes separately, monitoring stops after second
4. **Stop loss hit**: Both legs close via exitCallback
5. **Target hit**: Both legs close via exitCallback
6. **P&L diff threshold**: Both legs close via exitCallback

## Notes

- The `updatePriceWithDifferenceCheck()` method is ready to use but needs to be called from WebSocketService
- Currently, `updatePriceWithPnLDiffCheck()` is being used for P&L difference monitoring
- You can switch to or combine with `updatePriceWithDifferenceCheck()` for price-based exits

