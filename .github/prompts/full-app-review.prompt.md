# GitHub Copilot Task Prompt — Full Application Review: Improvements, Dead Code & Flow Fixes
> **Agent:** Claude Opus 4.6
> **Scope:** Entire application
> **Files to read first:**
> 1. `.github/copilot-instructions.md` — full project architecture
> 2. All files under `src/main/java/com/tradingbot/` (read each package top-down)
> 3. `src/main/resources/application.yml`
>
> Read all files before making any changes. Understand the complete picture first.

---

## Ground rules

- **Do not break any functional flow.** Every change must be safe and backward compatible.
- Changes are grouped into three tiers: **Dead code** (safe deletes), **Redundancy** (consolidate without behaviour change), and **Flow improvements** (targeted fixes with functional benefit).
- For each change, the agent must verify no other class references the thing being removed before deleting it.
- IST (`Asia/Kolkata`) for all time operations — never `ZoneId.systemDefault()`.
- `MarketDataEngine` is the primary data source for all strategy execution — never add direct `TradingService` calls in the strategy hot path.

---

## Tier 1 — Dead code: safe to delete

### 1A — `markStrategyAsCompleted()` in `StrategyService`

This method is marked deprecated in its own Javadoc and delegates entirely to
`handleStrategyCompletion()`. Search confirms zero callers outside `StrategyService` itself.

```java
// DELETE — no callers exist outside StrategyService:
public void markStrategyAsCompleted(String executionId, String reason) {
    log.debug("markStrategyAsCompleted invoked for {} ...(deprecated path)", ...);
    handleStrategyCompletion(executionId, StrategyCompletionReason.OTHER);
}
```

### 1B — Stale Javadoc references to `PositionMonitor` (v1)

`PositionMonitor.java` was deleted in the previous round. These comment references remain
and should be updated to `PositionMonitorV2`:

- `ExitContext.java` line 69: `// LEG STATE (snapshot from PositionMonitor)` → `PositionMonitorV2`
- `ExitStrategy.java`: any `@see` or `{@link}` referencing `PositionMonitor`
- `package-info.java` in the exit package: same
- `LegReplacementHandler.java`: `@param monitor PositionMonitor` in Javadoc

These are Javadoc-only changes — no logic change.

### 1C — `USE_DELTA_CACHE` constant in `BaseStrategy`

```java
private static final boolean USE_DELTA_CACHE = true;
```

This flag is always `true` and was never `false` in production. The conditional `if (USE_DELTA_CACHE && ...)` is therefore always true. Remove the constant and simplify the
condition to just `if (deltaCacheService != null)`.

### 1D — `BacktestEngine` — verify it is actively used

Confirm `BacktestController` is reachable (not behind a feature flag). If the backtest
feature is disabled in production (`backtest.enabled=false` in application.yml), add a
`@ConditionalOnProperty(name = "backtest.enabled", havingValue = "true")` to
`BacktestController` so it doesn't register endpoints unnecessarily. Do NOT delete
the backtest code — it is a real feature that is just toggled.

---

## Tier 2 — Redundancy: consolidate without behaviour change

### 2A — `getAvailableExpiries()` in `StrategyService` — route through `InstrumentCacheService`

`StrategyService.getAvailableExpiries()` calls `tradingService.getInstruments(EXCHANGE_NFO)`
directly — a live, un-cached Kite API call every time the frontend requests available
expiries. `InstrumentCacheService` already caches NFO instruments with a 5-minute TTL and
pre-warms the cache during market hours.

`StrategyService` does not currently inject `InstrumentCacheService`. Add it:

```java
// Add to StrategyService constructor and field declarations:
private final InstrumentCacheService instrumentCacheService;
```

Replace the live call in `getAvailableExpiries()`:

```java
// BEFORE:
List<Instrument> allInstruments = tradingService.getInstruments(EXCHANGE_NFO);

// AFTER:
List<Instrument> allInstruments = instrumentCacheService.getInstruments(EXCHANGE_NFO);
```

Also fix the `SimpleDateFormat` in the same method — it creates a new instance on every
call, which is wasteful and not thread-safe if the method is ever called concurrently.
Use the existing `SDF_YYYY_MM_DD` `ThreadLocal` that `BaseStrategy` already defines, or
add one to `StrategyService` itself:

```java
private static final ThreadLocal<SimpleDateFormat> SDF_EXPIRY =
    ThreadLocal.withInitial(() -> {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
        return sdf;
    });
```

Replace `new SimpleDateFormat("yyyy-MM-dd")` in `getAvailableExpiries()` with
`SDF_EXPIRY.get()`.

### 2B — `getAvailableInstruments()` in `StrategyService` — route through `InstrumentCacheService`

Same pattern as 2A. `getAvailableInstruments()` calls `tradingService.getInstruments(EXCHANGE_NFO)`
directly. Route through `instrumentCacheService.getInstruments(EXCHANGE_NFO)` instead.

### 2C — `BaseStrategy.instrumentCache` — replace with `InstrumentCacheService`

`BaseStrategy` maintains a private `HashMap<String, List<Instrument>> instrumentCache`
with a `synchronized` block. This is a per-instance, no-TTL, memory-leaking cache that
stores every expiry's instruments indefinitely for the lifetime of the strategy instance.

`InstrumentCacheService` already solves this correctly with a 5-minute TTL and exchange-level
caching. `BaseStrategy` already has `MarketDataEngine` injected. Add `InstrumentCacheService`
to `BaseStrategy`'s constructor parameters and replace `getInstrumentsForExpiry()` with
a call to `instrumentCacheService.getInstruments(EXCHANGE_NFO)`:

```java
// Add to BaseStrategy:
private final InstrumentCacheService instrumentCacheService;

// Replace getInstrumentsForExpiry() body:
private List<Instrument> getInstrumentsForExpiry(Date expiry) throws KiteException, IOException {
    SimpleDateFormat sdf = SDF_YYYY_MM_DD.get();
    String expiryKey = sdf.format(expiry);
    // Use shared InstrumentCacheService instead of per-instance HashMap
    List<Instrument> allNfo = instrumentCacheService.getInstruments(EXCHANGE_NFO);
    // Group and filter to the requested expiry (no caching needed — InstrumentCacheService handles it)
    return allNfo.stream()
        .filter(i -> i.getExpiry() != null && expiryKey.equals(sdf.format(i.getExpiry())))
        .collect(java.util.stream.Collectors.toList());
}
```

Then delete the `instrumentCache` field and all synchronized blocks around it:
```java
// DELETE:
private final Map<String, List<Instrument>> instrumentCache = new HashMap<>();
```

**Important:** `ATMStraddleStrategy`, `SellATMStraddleStrategy`, and `ShortStrangleStrategy`
all extend `BaseStrategy`. Update their constructors to pass `InstrumentCacheService` to
`super()`. Also update `StrategyFactory` where it creates strategy instances.

### 2D — `GET /api/market/instruments` (no exchange) — missed from previous round

The `{exchange}` variant was fixed to use `InstrumentCacheService` but the bare
`GET /api/market/instruments` endpoint still calls `tradingService.getInstruments()`
directly. Fix it to call `instrumentCacheService.getInstruments("NSE")` (or a reasonable
default exchange, or return an error asking the caller to specify an exchange):

```java
@GetMapping("/instruments")
public ResponseEntity<ApiResponse<List<Instrument>>> getInstruments()
        throws KiteException, IOException {
    // Use InstrumentCacheService — prevents live API call on every request
    List<Instrument> instruments = instrumentCacheService.getInstruments("NSE");
    return ResponseEntity.ok(ApiResponse.success(instruments));
}
```

### 2E — `GET /api/history/trading-mode` — missed from previous round

This endpoint is still present in `TradingHistoryController`. It returns trading mode,
which has nothing to do with history. `GET /api/paper-trading/status` serves the same
purpose. Delete the `getTradingMode()` method from `TradingHistoryController`.

The `UnifiedTradingService` field must stay — it is also used by `persistPositionSnapshot()`.

### 2F — Manual `userId == null` checks in `TradingHistoryController` — missed from previous round

`/api/history` was added to `UserContextFilter.PROTECTED_PREFIXES` in the previous round,
which means the filter now rejects requests without `X-User-Id` before they reach the
controller. The 8 manual null-checks are now redundant dead code:

```java
// DELETE all 8 occurrences of this pattern in TradingHistoryController:
String userId = CurrentUserContext.getUserId();
if (userId == null) {
    return ResponseEntity.badRequest()
            .body(ApiResponse.error("X-User-Id header is required"));
}
```

Replace each removed block with just `String userId = CurrentUserContext.getUserId();`
(the filter guarantees non-null for protected paths, so the null check is unnecessary).

For the four methods that currently don't check userId at all (`getAlertsByStrategy`,
`getConfigHistoryByStrategy`, `getSystemHealth`, `getLatestSystemHealth`) — these don't
need userId at all since they don't filter by user. Leave them as-is.

### 2G — `accumulateDailyPnl()` scope — extend to `SHORT_STRANGLE`

`accumulateDailyPnl()` in `StrategyService` only accumulates P&L for `SELL_ATM_STRADDLE`:

```java
if (execution.getStrategyType() == StrategyType.SELL_ATM_STRADDLE
        && execution.getProfitLoss() != null) {
```

`SHORT_STRANGLE` is also a short-volatility strategy with the same daily risk profile.
If a user runs `SHORT_STRANGLE`, their P&L does not count toward the daily gate, meaning
the `daily-max-loss` and `daily-max-profit` limits are silently bypassed.

Update the condition:

```java
if ((execution.getStrategyType() == StrategyType.SELL_ATM_STRADDLE
        || execution.getStrategyType() == StrategyType.SHORT_STRANGLE)
        && execution.getProfitLoss() != null) {
```

### 2H — `BacktestEngine` / `StrategyService.getAvailableExpiries` use `new Date()` for filtering

Both use `new Date()` to filter out past expiries. This is correct but should use
`ZonedDateTime.now(IST)` for consistency — `new Date()` is UTC, and around midnight IST
(18:30 UTC) this can incorrectly include or exclude same-day expiries:

```java
// BEFORE (ambiguous timezone):
.filter(i -> i.expiry.after(new Date()))

// AFTER (explicit IST):
Date nowIst = Date.from(ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).toInstant());
.filter(i -> i.expiry.after(nowIst))
```

---

## Tier 3 — Flow improvements

### 3A — `Thread.MAX_PRIORITY` in `SellATMStraddleStrategy` — document the limitation

`SellATMStraddleStrategy` sets exit order threads to `Thread.MAX_PRIORITY`. In the JVM,
thread priority is a hint to the OS scheduler and is not guaranteed. On Linux (where Cloud
Run containers run), Java thread priorities map to `nice` values but only take effect when
CPU is contended. Add a comment clarifying this so future developers don't rely on it for
latency guarantees:

```java
private static final ThreadFactory HFT_THREAD_FACTORY = r -> {
    Thread t = new Thread(r, "hft-sell-exit-" + THREAD_COUNTER.incrementAndGet());
    t.setDaemon(true);
    // NOTE: Thread.MAX_PRIORITY is a JVM hint to the OS scheduler.
    // On Linux (Cloud Run), this maps to a lower nice value but is not guaranteed.
    // Actual exit latency depends on system load, not thread priority alone.
    t.setPriority(Thread.MAX_PRIORITY);
    return t;
};
```

No functional change — documentation only.

### 3B — `BaseStrategy` backward-compatible constructor — remove the legacy chain

`BaseStrategy` has three constructors for backward compatibility, including one that
accepts `DeltaCacheService` without `MarketDataEngine` (the old path). Now that all
three strategies (`ATMStraddleStrategy`, `SellATMStraddleStrategy`, `ShortStrangleStrategy`)
inject both services, the legacy 2-arg constructor is unused.

Verify by searching all subclass constructors — if none call `super(tradingService, uts, lotSizeCache, deltaCacheService)` with only 4 args, delete the legacy constructor:

```java
// DELETE if no callers found:
protected BaseStrategy(TradingService tradingService,
                       UnifiedTradingService unifiedTradingService,
                       Map<String, Integer> lotSizeCache,
                       DeltaCacheService deltaCacheService) {
    this(tradingService, unifiedTradingService, lotSizeCache, deltaCacheService, null);
}
```

### 3C — `StrategyService.getAvailableExpiries()` — route through `MDE` nearest expiry cache

`MarketDataEngine` already pre-computes and caches the nearest weekly expiry per instrument
in `nearestExpiryCache`. However `getAvailableExpiries()` returns **all** future expiries
for display in the frontend dropdown — this requires the full instrument list, not just
nearest expiry. So the `InstrumentCacheService` route (Fix 2A) is correct for this method.

No additional change needed here — this is a clarification note only.

### 3D — `StrategyService` — validate `executionId` in `getStrategy()` and `stopStrategy()`

`getStrategy(executionId)` returns `null` if not found, and the controller returns 404.
But `stopStrategy(executionId)` does not check for null before proceeding, which can cause
a `NullPointerException` if the execution was already cleaned up. Add a guard:

```java
public Map<String, Object> stopStrategy(String executionId) throws KiteException {
    StrategyExecution execution = executionsById.get(executionId);
    if (execution == null) {
        // Already stopped or never existed — return a clear message
        return Map.of(
            "executionId", executionId,
            "status", "NOT_FOUND",
            "message", "Execution not found — may already be stopped or completed"
        );
    }
    // ... existing logic
}
```

Verify whether the current implementation already handles null before adding this.

### 3E — `LogoutService` — ensure daily P&L gate is reset on logout

`LogoutService` cleans up: strategies, scheduled restarts, WebSocket, paper account,
session. It does NOT reset `DailyPnlGateService`. If a user logs out and back in on the
same trading day, their cumulative P&L from before logout is still tracked, which is
correct for daily limits — do NOT reset it on logout.

However, `DailyPnlGateService` resets daily at midnight via `@Scheduled`. Confirm this
scheduled reset fires correctly by checking the cron expression. If it uses
`@Scheduled(cron = "0 0 0 * * ?")` without a timezone, it fires at midnight UTC (18:30
IST) not midnight IST. Fix if needed:

```java
// Ensure IST midnight reset:
@Scheduled(cron = "0 0 0 * * ?", zone = "Asia/Kolkata")
```

### 3F — `PositionMonitorV2` — log P&L on exit with trade context

Currently when an exit triggers, the log shows the reason and execution ID but not the
final P&L value. For a sell straddle trading bot this is the most important number to
see immediately in logs. Confirm whether the exit log includes `cumulativePnL` — if not,
add it to the exit trigger log line in `PositionMonitorV2`:

```java
log.info("[EXIT TRIGGERED] executionId={}, reason={}, pnl={}, exitStrategy={}",
    ctx.getExecutionId(), result.getReason(), ctx.getCumulativePnL(), result.getStrategyName());
```

---

## Implementation order

Work through tiers in order. Within each tier, work top-down.

Before each deletion, confirm with a search that the target has no callers outside its
own class/file. The codebase is ~30,000 lines — a missed reference will cause a compile
error at best, a silent runtime bug at worst.

After all changes, verify these flows still work end-to-end by tracing them mentally:

1. `POST /api/strategies/execute` → gates → order placement → `PositionMonitorV2` → exit → restart
2. `POST /api/auth/session` → `UserSessionManager` → `CurrentUserContext`
3. `GET /api/strategies/expiries/{instrument}` → `StrategyService.getAvailableExpiries()` → `InstrumentCacheService`
4. `MarketStateUpdater` @Scheduled → `NeutralMarketDetectorService` → `MarketStateEvent` → `StrategyRestartScheduler`

---

## Constraints

- Do not change `StrategyRequest`, `StrategyExecutionResponse`, `ApiResponse`, or `NeutralMarketResult` record shapes — the frontend depends on these
- Do not remove `DeltaCacheService` — it remains as Tier 2 fallback in `BaseStrategy`
- Do not add Spring Security — `UserContextFilter` is the auth mechanism
- Do not change the `PositionMonitorV2` exit strategy priority chain
- All new `ThreadLocal<SimpleDateFormat>` instances must use `Asia/Kolkata` timezone
- `InstrumentCacheService` injections must be added to constructors (not field injection) to maintain testability
