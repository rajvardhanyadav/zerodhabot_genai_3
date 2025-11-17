# Fix: Duplicate Exit Orders for Already-Closed Legs

## Problem
The `exitAllLegs()` method in both `ATMStraddleStrategy` and `ATMStrangleStrategy` was placing exit orders for legs that had already been closed individually. This resulted in duplicate/unnecessary exit orders.

## Root Cause
When an individual leg is closed due to hitting its loss threshold:
1. `PositionMonitor.exitIndividualLeg()` is triggered
2. The leg is removed from `monitor.legsBySymbol` map
3. An exit order is placed for that specific leg

However, when the remaining leg later hits the all-legs threshold (or target):
1. `exitAllLegs()` is triggered with the **original** call and put symbols
2. It blindly tries to exit both legs without checking if they're still active
3. This results in placing an exit order for a leg that was already closed

## Solution
Modified `exitAllLegs()` in both strategies to:

1. **Query the monitor** to get the current state of active legs
2. **Check if each leg is still being monitored** before placing exit order
3. **Skip exit order** if leg was already closed individually
4. **Log skip action** for observability

### Code Changes

**Before:**
```java
private void exitAllLegs(...) {
    // Blindly exit both legs
    OrderRequest callExitOrder = createOrderRequest(...);
    OrderResponse callExitResponse = unifiedTradingService.placeOrder(callExitOrder);
    
    OrderRequest putExitOrder = createOrderRequest(...);
    OrderResponse putExitResponse = unifiedTradingService.placeOrder(putExitOrder);
    // ...
}
```

**After:**
```java
private void exitAllLegs(...) {
    // Get monitor and check which legs are still active
    webSocketService.getMonitor(executionId).ifPresent(monitor -> {
        // Only exit Call leg if still being monitored
        if (monitor.getLegsBySymbol().containsKey(callSymbol)) {
            // Place exit order
        } else {
            log.info("Call leg already closed, skipping exit order");
        }
        
        // Only exit Put leg if still being monitored
        if (monitor.getLegsBySymbol().containsKey(putSymbol)) {
            // Place exit order
        } else {
            log.info("Put leg already closed, skipping exit order");
        }
    });
    // ...
}
```

## Scenario Example

### Previous Behavior (Buggy):
1. **T+0s**: ATM Straddle starts - Call leg + Put leg active
2. **T+30s**: Put leg hits -2.5 point loss → individual exit triggered
   - Put leg removed from monitor
   - Exit order placed for Put leg
3. **T+45s**: Call leg hits +4 point profit → all-legs exit triggered
   - `exitAllLegs()` tries to exit both Call AND Put
   - **BUG**: Duplicate exit order placed for Put (already closed)
   - Exit order placed for Call (correct)

### Fixed Behavior:
1. **T+0s**: ATM Straddle starts - Call leg + Put leg active
2. **T+30s**: Put leg hits -2.5 point loss → individual exit triggered
   - Put leg removed from monitor
   - Exit order placed for Put leg
3. **T+45s**: Call leg hits +4 point profit → all-legs exit triggered
   - `exitAllLegs()` checks monitor state
   - Put leg NOT in monitor → **skips exit order** ✅
   - Call leg in monitor → places exit order ✅

## Benefits

1. **No Duplicate Orders**: Prevents placing sell orders for already-closed positions
2. **Cleaner Logs**: Shows explicit skip messages when leg already closed
3. **Better Error Handling**: Wrapped each leg's exit in its own try-catch
4. **Broker Efficiency**: Reduces unnecessary API calls to broker
5. **Prevents Rejection**: Avoids broker rejecting orders for non-existent positions

## Files Modified

1. `src/main/java/com/tradingbot/service/strategy/ATMStraddleStrategy.java`
   - Updated `exitAllLegs()` method
   
2. `src/main/java/com/tradingbot/service/strategy/ATMStrangleStrategy.java`
   - Updated `exitAllLegs()` method

## Testing Recommendations

1. **Paper Trading Test**:
   - Start ATM Straddle with tight individual leg threshold
   - Let one leg hit individual loss threshold
   - Wait for other leg to hit profit threshold
   - Verify only ONE exit order placed for remaining leg
   - Check logs for "already closed, skipping exit order" message

2. **Edge Case Test**:
   - Start strategy with both legs
   - Manually close both legs individually via separate thresholds
   - Verify `exitAllLegs()` completes without placing any orders
   - Verify completion callback is still triggered

3. **Normal Case Test**:
   - Start strategy with both legs active
   - Let all-legs threshold trigger before any individual threshold
   - Verify both legs are exited normally

## Related Components

- **PositionMonitor**: Maintains the `legsBySymbol` map that tracks active legs
- **WebSocketService**: Provides access to monitor via `getMonitor(executionId)`
- **Individual Leg Exit**: Removes leg from monitor when individual threshold hit
- **All Legs Exit**: Now checks monitor state before placing orders

