# GitHub Copilot Task Prompt — Controller Audit: Dead Code, Redundancy & HFT Fixes
> **Agent:** Claude Opus 4.6
> **Files to read first:**
> 1. `.github/copilot-instructions.md` — project architecture
> 2. All files under `src/main/java/com/tradingbot/controller/`
> 3. `src/main/java/com/tradingbot/config/UserContextFilter.java`
> 4. `src/main/java/com/tradingbot/exception/GlobalExceptionHandler.java`
> 5. `src/main/java/com/tradingbot/service/InstrumentCacheService.java`
> 6. `src/main/java/com/tradingbot/service/UnifiedTradingService.java`
> 7. `src/main/java/com/tradingbot/util/ApiConstants.java`

---

## Overview

This prompt covers 8 independent fixes across the controllers and one service.
Each fix is self-contained — implement them in order, one at a time.

---

## Fix 1 — Delete `PositionMonitor.java` (dead code, 1552 lines)

`PositionMonitorV2` fully replaced `PositionMonitor`. No production code instantiates `PositionMonitor` — the only references are Javadoc comments.

**Delete these files entirely:**
- `src/main/java/com/tradingbot/service/strategy/monitoring/PositionMonitor.java`
- `src/test/java/com/tradingbot/service/strategy/monitoring/PositionMonitorTest.java`

**Then update these Javadoc-only references to point to `PositionMonitorV2`:**
- `src/main/java/com/tradingbot/service/strategy/monitoring/exit/ExitStrategy.java` — line referencing `PositionMonitor`
- `src/main/java/com/tradingbot/service/strategy/monitoring/exit/package-info.java`
- `src/main/java/com/tradingbot/service/SellATMStraddleStrategy.java` — comment "Entry/exit thresholds match PositionMonitor"
- `src/main/java/com/tradingbot/service/strategy/LegReplacementHandler.java` — `@param monitor` Javadoc

No logic changes — Javadoc text updates only.

---

## Fix 2 — Remove duplicate `logKiteExceptionDetails()` from `StrategyController`

`GlobalExceptionHandler` already handles `KiteException` globally with identical box-drawing log output. The copy in `StrategyController` is never reached because exceptions propagate to the global handler.

In `StrategyController.java`:

1. Delete the entire private `logKiteExceptionDetails()` method.

2. Simplify `getInstruments()` — remove the try/catch and let the exception bubble naturally:

```java
// BEFORE:
public ResponseEntity<ApiResponse<List<InstrumentInfo>>> getInstruments() {
    log.debug(ApiConstants.LOG_GET_INSTRUMENTS_REQUEST);
    List<StrategyService.InstrumentDetail> instrumentDetails = null;
    try {
        instrumentDetails = strategyService.getAvailableInstruments();
    } catch (KiteException e) {
        logKiteExceptionDetails(e);
        throw new RuntimeException(e);
    }
    ...
}

// AFTER:
public ResponseEntity<ApiResponse<List<InstrumentInfo>>> getInstruments()
        throws KiteException, IOException {
    log.debug(ApiConstants.LOG_GET_INSTRUMENTS_REQUEST);
    List<StrategyService.InstrumentDetail> instrumentDetails =
            strategyService.getAvailableInstruments();
    return ResponseEntity.ok(ApiResponse.success(
            instrumentDetails.stream()
                .map(d -> new InstrumentInfo(d.code(), d.name(), d.lotSize(), d.strikeInterval()))
                .collect(Collectors.toList())));
}
```

---

## Fix 3 — Remove the `@NotNull` / null-default contradiction in `StrategyController`

`StrategyRequest.strategyType` is annotated `@NotNull` and `@Valid` is on the controller parameter. The null-default code block therefore can never execute — Spring rejects the request before the controller body runs.

In `StrategyController.executeStrategy()`, delete these lines:

```java
// DELETE THIS BLOCK — @NotNull + @Valid means this is unreachable:
if (request.getStrategyType() == null) {
    request.setStrategyType(StrategyType.ATM_STRADDLE);
}
```

---

## Fix 4 — Fix timezone bug in `MarketDataController.parseDate()`

`ZoneId.systemDefault()` on a Cloud Run (UTC) server returns midnight UTC, which is
5.5 hours before midnight IST. Historical data requests will silently fetch the wrong
day's candles.

In `MarketDataController.java`, fix the `parseDate()` helper:

```java
private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

private Date parseDate(String dateStr) throws DateTimeParseException {
    LocalDate localDate = LocalDate.parse(dateStr);
    return Date.from(localDate.atStartOfDay(IST).toInstant());
}
```

---

## Fix 5 — Route `GET /api/market/instruments` through `InstrumentCacheService`

Both `GET /api/market/instruments` and `GET /api/market/instruments/{exchange}` currently
call `tradingService.getInstruments()` directly — live Kite API calls returning 50,000+
instruments with no caching. During market hours this competes with strategy execution
for the Kite rate budget.

`InstrumentCacheService` already caches instruments with a 5-minute TTL.
Wire it into `MarketDataController`:

```java
// Add to MarketDataController fields:
private final InstrumentCacheService instrumentCacheService;

// Update constructor:
public MarketDataController(TradingService tradingService,
                             MarketDataEngine marketDataEngine,
                             InstrumentCacheService instrumentCacheService) {
    this.tradingService = tradingService;
    this.marketDataEngine = marketDataEngine;
    this.instrumentCacheService = instrumentCacheService;
}
```

Replace both instrument endpoint implementations:

```java
@GetMapping("/instruments")
public ResponseEntity<ApiResponse<List<Instrument>>> getInstruments()
        throws KiteException, IOException {
    // Routes through InstrumentCacheService (5-min TTL) — not a live API call
    List<Instrument> instruments = instrumentCacheService.getInstruments("NSE");
    return ResponseEntity.ok(ApiResponse.success(instruments));
}

@GetMapping("/instruments/{exchange}")
public ResponseEntity<ApiResponse<List<Instrument>>> getInstrumentsByExchange(
        @PathVariable String exchange) throws KiteException, IOException {
    List<Instrument> instruments = instrumentCacheService.getInstruments(exchange.toUpperCase());
    return ResponseEntity.ok(ApiResponse.success(instruments));
}
```

---

## Fix 6 — Remove `GET /api/history/trading-mode` from `TradingHistoryController`

Trading mode has no relation to trading history. This endpoint is misplaced and
duplicates `/api/paper-trading/status`.

In `TradingHistoryController.java`:
1. Delete the `getTradingMode()` method and its `@GetMapping("/trading-mode")` annotation.
2. Remove the `UnifiedTradingService` field **only if** it is used nowhere else in `TradingHistoryController`.
   Check: `UnifiedTradingService` is also used by `persistPositionSnapshot()` — so keep the field.
   Only remove the `getTradingMode()` method itself.

---

## Fix 7 — Extract repeated `userId == null` guard in `TradingHistoryController`

The following block is copy-pasted 8 times across `TradingHistoryController`:

```java
String userId = CurrentUserContext.getUserId();
if (userId == null) {
    return ResponseEntity.badRequest()
            .body(ApiResponse.error("X-User-Id header is required"));
}
```

**Note:** `/api/history` is NOT in `UserContextFilter.PROTECTED_PREFIXES`, so the filter
does not enforce the header for history endpoints. Either:

**Option A (Recommended):** Add `"/api/history"` to `UserContextFilter.PROTECTED_PREFIXES`
so the filter enforces the header before the request reaches the controller. Then remove
all 8 manual null-checks from `TradingHistoryController`. The `getAlertsByStrategy()`,
`getConfigHistoryByStrategy()`, `getSystemHealth()`, and `getLatestSystemHealth()` methods
don't check userId at all currently — this fix also closes that inconsistency.

```java
// In UserContextFilter.java, add to PROTECTED_PREFIXES:
private static final Set<String> PROTECTED_PREFIXES = Set.of(
        "/api/orders",
        "/api/portfolio",
        "/api/market",
        "/api/account",
        "/api/gtt",
        "/api/strategies",
        "/api/monitoring",
        "/api/history"   // ← ADD THIS
);
```

Then in `TradingHistoryController.java`, remove all 8 occurrences of:
```java
String userId = CurrentUserContext.getUserId();
if (userId == null) {
    return ResponseEntity.badRequest()
            .body(ApiResponse.error("X-User-Id header is required"));
}
```
Replace each removed block with just:
```java
String userId = CurrentUserContext.getRequiredUserId();
```
(Use `CurrentUserContext.getRequiredUserId()` if it exists, or `CurrentUserContext.getUserId()`
— by this point the filter has already guaranteed it's non-null.)

**Option B (Simpler, no filter change):** Extract to a private helper that throws:
```java
private String requireUserId() {
    String userId = CurrentUserContext.getUserId();
    if (userId == null) {
        throw new IllegalArgumentException("X-User-Id header is required");
    }
    return userId;
}
```
`GlobalExceptionHandler` already handles `IllegalArgumentException` with a 400 response.
Replace all 8 copy-paste blocks with `String userId = requireUserId();`.

**Implement Option A** — it's the architecturally correct fix and closes the missing
protection on the unauthenticated history endpoints (`getAlertsByStrategy`, etc.).

---

## Fix 8 — Make paper trading mode toggle thread-safe in `PaperTradingController`

`PaperTradingConfig` is a `@ConfigurationProperties` bean annotated with Lombok `@Data`,
which generates a non-synchronized setter. Calling `paperTradingConfig.setPaperTradingEnabled()`
from concurrent requests is a race condition.

`UnifiedTradingService.isPaperTradingEnabled()` reads directly from `config.isPaperTradingEnabled()`,
so it will see the updated value — no change needed there.

Fix: add an `AtomicBoolean` to `PaperTradingConfig` as a thread-safe runtime override,
and update `UnifiedTradingService` to read from it.

In `PaperTradingConfig.java`, add:

```java
import java.util.concurrent.atomic.AtomicBoolean;

// Add below the existing paperTradingEnabled field:
/**
 * Runtime override for paper trading mode. When set (non-null), overrides
 * the static paperTradingEnabled config value. Thread-safe for runtime toggling.
 */
private final AtomicBoolean runtimeOverride = new AtomicBoolean();
private volatile boolean runtimeOverrideSet = false;

/**
 * Thread-safe runtime toggle. Overrides the static config value.
 * Called by PaperTradingController.setTradingMode().
 */
public void setRuntimePaperTradingEnabled(boolean enabled) {
    runtimeOverride.set(enabled);
    runtimeOverrideSet = true;
}

/**
 * Returns effective paper trading state: runtime override if set, else static config.
 */
public boolean isEffectivelyPaperTradingEnabled() {
    return runtimeOverrideSet ? runtimeOverride.get() : paperTradingEnabled;
}
```

In `UnifiedTradingService.java`, update `isPaperTradingEnabled()`:

```java
public boolean isPaperTradingEnabled() {
    return config.isEffectivelyPaperTradingEnabled();
}
```

In `PaperTradingController.java`, update `setTradingMode()` to use the thread-safe method:

```java
// BEFORE:
paperTradingConfig.setPaperTradingEnabled(paperTradingEnabled);

// AFTER:
paperTradingConfig.setRuntimePaperTradingEnabled(paperTradingEnabled);
```

---

## Fix 9 — Remove `getInfo()` duplication in `PaperTradingController`

`GET /api/paper-trading/info` returns the union of what `/status` and `/statistics`
already return. The `buildStatisticsMap()` logic is duplicated inline.

Simplify `getInfo()` to delegate to the existing methods:

```java
@GetMapping("/info")
public ResponseEntity<ApiResponse<Map<String, Object>>> getInfo() {
    boolean isPaperMode = unifiedTradingService.isPaperTradingEnabled();
    Map<String, Object> info = new HashMap<>();

    info.put("mode", isPaperMode ? "PAPER_TRADING" : "LIVE_TRADING");
    info.put("paperTradingEnabled", isPaperMode);
    info.put("description", isPaperMode
        ? "Paper Trading Mode: All orders are simulated using real-time market data"
        : "Live Trading Mode: Orders are placed on actual exchange via Kite API");

    if (isPaperMode) {
        PaperAccount account = unifiedTradingService.getPaperAccount();
        if (account != null) {
            info.put("account", buildStatisticsMap(account));
        }
    }

    return ResponseEntity.ok(ApiResponse.success(info));
}
```

Remove the duplicate `buildStatisticsMap()` call that was inline here — it's already
extracted as a private method. This fix only removes the emoji characters (📊 💰) from
the description strings — they are non-standard in API responses.

---

## Fix 10 — Pull version from properties in `HealthController`

The hardcoded `"version", "4.1"` in `HealthController.health()` is stale.

In `application.yml`, add under `spring.application`:
```yaml
spring:
  application:
    name: zerodha-trading-bot
    version: "4.5"
```

In `HealthController.java`:
```java
// Add field:
@Value("${spring.application.version:unknown}")
private String appVersion;

// Update health() return:
return Map.of(
    "status", "UP",
    "timestamp", LocalDateTime.now(ZoneId.of("Asia/Kolkata")),
    "application", "Zerodha Trading Bot",
    "version", appVersion,
    "environment", System.getenv("K_SERVICE") != null ? "cloud-run" : "local"
);
```

Also add the `@Value` import and remove the hardcoded `"production"` environment string —
the Cloud Run environment variables `K_SERVICE` / `K_REVISION` already checked in
`sessionDiagnostics()` are the right source. This makes both health endpoints consistent.

---

## Implementation order and constraints

Implement in this order — each fix is independent but ordered by risk (safest first):

1. Fix 1 (delete dead code) — no logic change, pure deletion
2. Fix 2 (remove duplicate exception logger) — removes dead catch block
3. Fix 3 (remove unreachable null-default) — removes unreachable code
4. Fix 4 (timezone bug) — one-line fix, high value
5. Fix 5 (instrument caching) — routes through existing cache
6. Fix 6 (remove misplaced endpoint) — pure deletion
7. Fix 7 (userId guard extraction) — modifies filter + controller
8. Fix 8 (thread-safe mode toggle) — adds `AtomicBoolean` to config
9. Fix 9 (getInfo delegation) — simplifies existing method
10. Fix 10 (version from properties) — adds @Value + yml entry

**Constraints:**
- Do not change any method signatures visible to the frontend (response shapes must stay identical)
- Do not add Spring Security — `UserContextFilter` is the auth mechanism, keep it
- IST timezone (`Asia/Kolkata`) for all time operations — never `ZoneId.systemDefault()`
- All exceptions must continue to propagate to `GlobalExceptionHandler` — do not add new try/catch blocks in controllers
- `buildStatisticsMap()` in `PaperTradingController` must remain as a private method — it is used by both `getStatistics()` and `getInfo()`
