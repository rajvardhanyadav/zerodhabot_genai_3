# Paper Trading Auto-Restart Implementation

## Summary
Implemented auto-restart functionality for paper trading strategies that matches the behavior of live trading. When a paper trading strategy completes due to target/stoploss being hit, the system now automatically schedules a new strategy execution on the next 5-minute candle, respecting the configured limits and mode-specific toggles.

## Changes Made

### 1. **StrategyExecution Model** (`src/main/java/com/tradingbot/model/StrategyExecution.java`)
   - Added `tradingMode` field to track whether execution ran in PAPER or LIVE mode
   - Added helper methods `isPaperTradingMode()` and `isLiveTradingMode()`
   - Trading mode is captured at execution creation time, not queried from global state

### 2. **StrategyService** (`src/main/java/com/tradingbot/service/StrategyService.java`)
   - Injects `ApplicationEventPublisher` to publish strategy completion events
   - Sets `tradingMode` on every execution in `createAndRegisterExecution()`
   - Publishes `StrategyCompletionEvent` in `handleStrategyCompletion()`
   - Created nested record `StrategyCompletionEvent(Object source, StrategyExecution execution)`

### 3. **StrategyRestartScheduler** (`src/main/java/com/tradingbot/service/strategy/StrategyRestartScheduler.java`)
   - Removed dependency on `UnifiedTradingService` (no longer queries global paper/live state)
   - Added `@EventListener` method `onStrategyCompletion()` to listen for completion events
   - Updated `scheduleRestart()` to use execution's stored `tradingMode` instead of global flag
   - Properly respects `autoRestartPaperEnabled` and `autoRestartLiveEnabled` config toggles
   - Fixed deprecated API usage (changed from `schedule(Runnable, Date)` to `schedule(Runnable, Instant)`)

### 4. **Tests** (`src/test/java/com/tradingbot/service/strategy/StrategyRestartSchedulerTest.java`)
   - Created comprehensive unit tests covering:
     - Paper mode restart scheduling when enabled
     - Paper mode NOT scheduling when disabled
     - Live mode restart scheduling when enabled
     - Manual stop does not trigger restart
     - Max restart limit prevents further scheduling

## Configuration

The following configuration properties control auto-restart behavior:

```yaml
strategy:
  # Global enable/disable for auto-restart
  auto-restart-enabled: true
  
  # Enable auto-restart for paper trading mode
  auto-restart-paper-enabled: true
  
  # Enable auto-restart for live trading mode (keep false until validated)
  auto-restart-live-enabled: false
  
  # Maximum number of auto-restarts per execution chain (0 = unlimited)
  max-auto-restarts: 50
```

## Behavior

### Paper Trading
- When `auto-restart-enabled: true` and `auto-restart-paper-enabled: true`:
  - Strategy completes with `TARGET_HIT` → schedules restart on next 5m candle
  - Strategy completes with `STOPLOSS_HIT` → schedules restart on next 5m candle
  - Strategy completes with `MANUAL_STOP` → does NOT schedule restart
  - Strategy completes with `ERROR` or `OTHER` → does NOT schedule restart
  - Restart count increments with each auto-restart
  - Stops scheduling when `autoRestartCount >= maxAutoRestarts` (if maxAutoRestarts > 0)

### Live Trading
- Same behavior as paper, but controlled by `auto-restart-live-enabled` flag
- Recommended to keep `auto-restart-live-enabled: false` until thoroughly validated in paper mode

## Event Flow

```
1. Strategy executes (paper or live)
   └─> StrategyExecution created with tradingMode set
   
2. Monitor detects target/stoploss hit
   └─> Calls completionCallback with StrategyCompletionReason
   
3. StrategyService.handleStrategyCompletion()
   ├─> Sets execution status to COMPLETED
   ├─> Sets completionReason
   └─> Publishes StrategyCompletionEvent
   
4. StrategyRestartScheduler.onStrategyCompletion() (event listener)
   ├─> Validates auto-restart enabled
   ├─> Validates mode-specific toggle (paper/live)
   ├─> Validates completion reason (TARGET_HIT or STOPLOSS_HIT)
   ├─> Validates max restart count not exceeded
   ├─> Calculates next 5-minute candle time
   └─> Schedules restart task
   
5. Scheduled task executes on next candle
   └─> Calls StrategyService.executeStrategy() with new ATM_STRADDLE request
```

## Testing

Run the new test suite:
```bash
mvn test -Dtest=StrategyRestartSchedulerTest
```

## Notes

- The `tradingMode` field on `StrategyExecution` is set once at creation and never changes, ensuring correct restart behavior even if global paper/live toggle is changed mid-execution
- The system uses Spring's event publishing mechanism (`@EventListener`) to decouple restart scheduling from strategy completion
- Historical replay service still manually calls `scheduleRestart()` after replay completes
- Paper and live executions are independent; switching modes doesn't affect already-scheduled restarts

## Future Enhancements

- Consider persisting restart count across app restarts (currently in-memory only)
- Add metrics/monitoring for restart success/failure rates
- Add configurable delay between restarts (currently always next 5m candle)
- Add UI toggle to enable/disable auto-restart per execution

