# Enhancement: Cancel Scheduled Auto-Restarts on Stop

## Summary
Added functionality to cancel any scheduled auto-restarts when stopping strategies (both single stop and stop-all operations). This prevents unwanted strategy restarts after a user explicitly stops their strategies.

## Changes Made

### 1. **StrategyRestartScheduler** - New Methods

Added the following public methods to manage scheduled restarts:

#### `cancelScheduledRestart(String executionId)`
- Cancels a scheduled restart for a specific execution
- Returns `true` if a restart was cancelled, `false` if none was scheduled
- Logs the cancellation action

#### `cancelScheduledRestartsForUser(String userId)`
- Cancels ALL scheduled restarts for a specific user
- Iterates through both active and completed executions
- Returns count of cancelled restarts
- Useful when user wants to stop all their strategies

#### `cancelAllScheduledRestarts()`
- Admin/system function to cancel all scheduled restarts across all users
- Returns count of cancelled restarts
- Clears the entire scheduled restarts map

#### Helper Methods:
- `getScheduledRestartsCount()` - Get total count of scheduled restarts
- `getScheduledRestartsCountForUser(String userId)` - Get count for specific user

### 2. **StrategyService** - Updated Stop Methods

#### `stopStrategy(String executionId)`
**Before:**
- Only closed the strategy's open positions
- No handling of scheduled restarts

**After:**
- Closes the strategy's open positions
- Calls `strategyRestartScheduler.cancelScheduledRestart(executionId)`
- Adds `scheduledRestartCancelled` field to response
- Logs whether a scheduled restart was cancelled

#### `stopAllActiveStrategies()`
**Before:**
- Only closed all active strategies for the user
- No handling of scheduled restarts

**After:**
- Closes all active strategies for the user
- Calls `strategyRestartScheduler.cancelScheduledRestartsForUser(userId)`
- Adds `cancelledScheduledRestarts` field to response (count)
- Handles case where no active strategies exist but scheduled restarts might
- Logs count of cancelled scheduled restarts

### 3. **Dependency Injection**
- Added `@Lazy` injected `StrategyRestartScheduler` to `StrategyService`
- Used `@Lazy` to avoid circular dependency (scheduler depends on service)

## Use Cases

### Scenario 1: Single Strategy Stop
```
1. User starts ATM Straddle strategy
2. Strategy hits target after 2 minutes
3. Auto-restart scheduled for next 5-minute candle (3 minutes away)
4. User calls /api/strategies/stop/{executionId}
5. System:
   - Closes any open positions
   - Cancels the scheduled restart
   - Returns scheduledRestartCancelled: true
```

### Scenario 2: Stop All Strategies
```
1. User has 3 active strategies running
2. All 3 hit their targets and have auto-restarts scheduled
3. User calls DELETE /api/strategies/stop-all
4. System:
   - Closes all open positions across 3 strategies
   - Cancels all 3 scheduled restarts
   - Returns cancelledScheduledRestarts: 3
```

### Scenario 3: Stop All with No Active Strategies
```
1. User had 2 strategies that completed
2. Both have auto-restarts scheduled for future candles
3. No currently ACTIVE strategies (both COMPLETED)
4. User calls DELETE /api/strategies/stop-all
5. System:
   - Finds no active strategies to close
   - Still cancels the 2 scheduled restarts
   - Returns totalStrategies: 0, cancelledScheduledRestarts: 2
```

## API Response Changes

### `/api/strategies/stop/{executionId}` Response
```json
{
  "executionId": "abc-123",
  "totalLegs": 2,
  "successCount": 2,
  "failureCount": 0,
  "scheduledRestartCancelled": true,  // NEW FIELD
  "status": "success",
  "exitOrders": [...]
}
```

### `/api/strategies/stop-all` Response
```json
{
  "message": "Stopped 3 strategies",
  "totalStrategies": 3,
  "totalLegsClosedSuccessfully": 6,
  "totalLegsFailed": 0,
  "cancelledScheduledRestarts": 3,  // NEW FIELD
  "results": [...]
}
```

## Benefits

1. **Prevents Unwanted Restarts**: When user explicitly stops strategies, they won't mysteriously restart
2. **Clean State**: No orphaned scheduled tasks after user stops all strategies
3. **User Control**: User can stop auto-restart behavior without disabling it globally
4. **Observability**: Response includes information about cancelled restarts
5. **Resource Efficiency**: Cancelled tasks free up scheduler resources

## Implementation Details

### Concurrency Handling
- Uses `ConcurrentHashMap` for `scheduledRestarts` map (thread-safe)
- Uses `List.copyOf()` to avoid concurrent modification when iterating
- Each `ScheduledFuture` is cancelled with `cancel(false)` (don't interrupt)

### User Isolation
- Only cancels restarts for the authenticated user
- Uses `CurrentUserContext.getRequiredUserId()` for user isolation
- Admin function `cancelAllScheduledRestarts()` available if needed

### Circular Dependency Prevention
- Used `@Lazy` injection of `StrategyRestartScheduler` in `StrategyService`
- This breaks the circular dependency:
  - StrategyService → StrategyRestartScheduler
  - StrategyRestartScheduler → StrategyService

## Testing Recommendations

1. **Test Single Strategy Stop**:
   - Start strategy, let it complete with target
   - Verify restart is scheduled (check logs)
   - Stop the strategy manually
   - Verify `scheduledRestartCancelled: true` in response
   - Wait past the scheduled time
   - Verify no restart occurs

2. **Test Stop All**:
   - Start 2 strategies
   - Let both complete and schedule restarts
   - Call stop-all
   - Verify `cancelledScheduledRestarts: 2` in response
   - Wait past scheduled times
   - Verify no restarts occur

3. **Test Stop All with No Active**:
   - Have completed strategies with scheduled restarts
   - Call stop-all when no ACTIVE strategies exist
   - Verify restarts still get cancelled
   - Check response shows 0 strategies stopped but N restarts cancelled

## Files Modified

1. **StrategyRestartScheduler.java**
   - Added `cancelScheduledRestart()`
   - Added `cancelScheduledRestartsForUser()`
   - Added `cancelAllScheduledRestarts()`
   - Added getter methods for restart counts

2. **StrategyService.java**
   - Injected `StrategyRestartScheduler` (lazy)
   - Updated `stopStrategy()` to cancel restart
   - Updated `stopAllActiveStrategies()` to cancel all user restarts
   - Added response fields for cancelled restart info

## Backward Compatibility

✅ **Fully Backward Compatible**
- Existing API contracts remain unchanged (only added fields)
- No breaking changes to request/response structures
- New fields in response are additions, not modifications
- Clients ignoring new fields will continue to work

## Future Enhancements

1. Add GET endpoint to view scheduled restarts for current user
2. Add endpoint to manually cancel specific scheduled restart without stopping strategy
3. Add WebSocket notification when restart is cancelled
4. Add metrics/monitoring for cancelled restart counts

