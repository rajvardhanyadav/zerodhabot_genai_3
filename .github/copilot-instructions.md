# GitHub Copilot Instructions — Zerodha Trading Bot

> **Agent:** Claude Opus 4.6  
> **Purpose:** Onboarding prompt for AI-assisted development in IntelliJ IDEA.  
> Save this file at `.github/copilot-instructions.md` in the project root.

---

## 1. Project Identity

This is **`zerodha-trading-bot`** — a production-grade, intraday options trading bot for NIFTY 50 on the Indian derivatives market, built with **Java 17 / Spring Boot** and integrated with the **Zerodha Kite Connect API**.

The bot executes options strategies (Sell ATM Straddle, Short Strangle, ATM Straddle) in both **live** and **paper trading** modes, with a high-frequency-trading (HFT)-oriented architecture that prioritises sub-100ms execution latency.

---

## 2. Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.x |
| Build | Maven |
| ORM | Spring Data JPA + Hibernate 6 |
| DB (dev) | H2 File (`./data/tradingbot`) |
| DB (prod) | PostgreSQL |
| Migrations | Flyway (prod only; dev uses `ddl-auto: update`) |
| Connection Pool | HikariCP (pool: 15 dev / 20 prod) |
| Broker API | Zerodha Kite Connect Java SDK |
| Concurrency | `ScheduledExecutorService`, `CompletableFuture`, `ConcurrentHashMap` |
| Docs | SpringDoc OpenAPI / Swagger (`/swagger-ui.html`) |
| IDE | IntelliJ IDEA |
| Logging | SLF4J + Logback (`@Slf4j` via Lombok) |

---

## 3. Package Structure

```
com.tradingbot
├── TradingBotApplication.java          # Spring Boot entry point
│
├── config/                             # All @Configuration / @ConfigurationProperties
│   ├── MarketDataEngineConfig          # market-data-engine.* properties
│   ├── StrategyConfig                  # strategy.* properties
│   ├── VolatilityConfig                # volatility.* + auto-square-off + trailing SL
│   ├── NeutralMarketConfig             # neutral-market.* properties
│   ├── KiteConfig                      # kite.* (API key, secret, token)
│   ├── PaperTradingConfig              # trading.* (paper mode, charges, slippage)
│   ├── PersistenceConfig               # persistence.* properties
│   ├── SwaggerConfig / SwaggerGlobalHeaderConfig
│   ├── CorsConfig
│   ├── AsyncPersistenceConfig          # Async thread pool for persistence writes
│   └── UserContextFilter               # MDC / per-request user context
│
├── controller/                         # REST controllers (@RestController)
│   ├── AuthController                  # POST /auth/login, /auth/logout
│   ├── StrategyController              # POST /strategy/execute, /strategy/stop
│   ├── MarketDataController            # GET /market/spot, /market/option-chain
│   ├── OrderController                 # Order management
│   ├── PortfolioController             # Holdings, positions
│   ├── AccountController               # Account info, funds
│   ├── PaperTradingController          # Paper trading state & reset
│   ├── TradingHistoryController        # Trade history queries
│   ├── MonitoringController            # System health endpoints
│   ├── GTTController                   # Good-Till-Triggered orders
│   └── HealthController                # /actuator/health wrapper
│
├── service/                            # Core business logic
│   ├── MarketDataEngine                # ⭐ Central HFT cache engine (see §6)
│   ├── TradingService                  # Direct Kite API wrapper
│   ├── UnifiedTradingService           # Routes: Paper vs Live
│   ├── StrategyService                 # Strategy lifecycle management
│   ├── InstrumentCacheService          # Instrument token lookup cache
│   ├── BotStatusService                # Bot start/stop/status
│   ├── RateLimiterService              # Kite API rate limiting
│   ├── LogoutService                   # Session cleanup
│   ├── TradingConstants                # String constants (exchanges, order types)
│   │
│   ├── session/
│   │   └── UserSessionManager          # Multi-user session & Kite token management
│   │
│   ├── greeks/
│   │   └── DeltaCacheService           # Black-Scholes delta pre-computation cache
│   │
│   ├── strategy/                       # Strategy implementations
│   │   ├── TradingStrategy             # Interface: execute(), stop()
│   │   ├── BaseStrategy                # Abstract base — common instrument/order utils
│   │   ├── SellATMStraddleStrategy     # ⭐ Primary strategy: Sell CE + Sell PE at ATM
│   │   ├── ATMStraddleStrategy         # Buy CE + Buy PE at ATM
│   │   ├── ShortStrangleStrategy       # Sell 0.4Δ CE + Sell 0.4Δ PE + Hedge legs
│   │   ├── StrategyFactory             # Creates strategy instances by StrategyType enum
│   │   ├── StraddleExitHandler         # Handles exit order execution
│   │   ├── LegReplacementHandler       # Roll/replace individual legs
│   │   ├── MonitoringSetupHelper       # Wires up PositionMonitorV2
│   │   ├── MarketStateUpdater          # Publishes MarketStateEvent (neutral/trending)
│   │   ├── StrategyRestartScheduler    # Listens for neutral market → re-entry
│   │   ├── DailyPnlGateService         # Halts restart if daily P&L limit hit
│   │   ├── NeutralMarketDetectorService # 5-signal neutral market scoring engine
│   │   └── VolatilityFilterService     # India VIX filter before entry
│   │
│   │   └── monitoring/
│   │       ├── WebSocketService        # Kite WebSocket tick subscription
│   │       ├── PositionMonitor         # Legacy tick-driven exit monitor
│   │       ├── PositionMonitorV2       # ⭐ HFT: Strategy-pattern exit evaluation
│   │       ├── LegMonitor              # Individual option leg tracker
│   │       └── exit/                   # Exit strategy implementations
│   │           ├── ExitStrategy        # Interface: evaluate(ExitContext) → ExitResult
│   │           ├── ExitContext         # Shared state passed to all exit strategies
│   │           ├── ExitResult          # Result: shouldExit, reason, isStopLoss
│   │           ├── AbstractExitStrategy
│   │           ├── PointsBasedExitStrategy     # Fixed-point target + SL (priority 100/400)
│   │           ├── PremiumBasedExitStrategy    # % premium decay/expansion (priority 50)
│   │           ├── TimeBasedForcedExitStrategy # Auto square-off at configured time (priority 0)
│   │           └── TrailingStopLossStrategy    # Dynamic trailing SL (priority 300)
│   │
│   └── persistence/
│       ├── TradePersistenceService     # Async trade/order write-through to DB
│       ├── PersistenceBufferService    # Buffer + batch DB writes
│       ├── EndOfDayPersistenceService  # EOD P&L summary
│       ├── SystemHealthMonitorService  # Periodic health snapshots
│       └── DataCleanupService          # Scheduled old-data pruning
│
├── paper/                              # Paper trading simulation
│   ├── PaperTradingService             # In-memory order/position/P&L simulation
│   ├── PaperAccount / PaperOrder / PaperPosition
│   └── ZerodhaChargeCalculator         # Realistic charge computation
│
├── backtest/                           # Backtesting engine
│   ├── engine/BacktestEngine           # Historical replay engine
│   ├── engine/HistoricalDataFetcher    # Kite historical candle API
│   ├── engine/InstrumentResolver
│   ├── adapter/HistoricalCandleAdapter # Adapts historical candles to tick feed
│   ├── adapter/TickFeedMerger
│   ├── service/BacktestService
│   └── controller/BacktestController
│
├── entity/                             # JPA entities (@Entity)
│   ├── TradeEntity, OrderLegEntity, OrderTimingEntity
│   ├── StrategyExecutionEntity, StrategyConfigHistoryEntity
│   ├── DeltaSnapshotEntity, MTMSnapshotEntity
│   ├── PositionSnapshotEntity, DailyPnLSummaryEntity
│   ├── AlertHistoryEntity, WebSocketEventEntity
│   ├── SystemHealthSnapshotEntity
│   └── UserSessionEntity
│
├── repository/                         # Spring Data JPA repositories
│   └── (one repository per entity, standard JpaRepository<Entity, Long>)
│
├── dto/                                # Request/response DTOs
│   ├── ApiResponse<T>                  # Standard wrapper: {success, data, message}
│   ├── StrategyRequest / StrategyExecutionResponse
│   ├── OrderRequest / OrderResponse
│   ├── BasketOrderRequest / BasketOrderResponse
│   └── (others: LoginRequest, DayPnLResponse, BotStatusResponse, etc.)
│
├── model/                              # Domain enums and value objects
│   ├── StrategyType                    # ATM_STRADDLE, SELL_ATM_STRADDLE, SHORT_STRANGLE
│   ├── StrategyStatus                  # RUNNING, STOPPED, COMPLETED, ERROR
│   ├── StrategyCompletionReason        # TARGET, STOP_LOSS, TIME_BASED, MANUAL, etc.
│   ├── SlTargetMode                    # POINTS, PREMIUM_PCT
│   ├── BotStatus
│   └── MarketStateEvent                # NEUTRAL / TRENDING (published by MarketStateUpdater)
│
├── util/
│   ├── TradingConstants               # Exchange/order/product type string constants
│   ├── StrategyConstants              # Strategy-specific constants
│   ├── CandleUtils                    # OHLCV candle computation helpers
│   └── CurrentUserContext             # ThreadLocal current user (from UserContextFilter)
│
└── exception/
    └── GlobalExceptionHandler          # @ControllerAdvice — maps exceptions to ApiResponse
```

---

## 4. Configuration System

All behaviour is driven by `application.yml`. Key prefixes:

| Prefix | Config Class | Purpose |
|---|---|---|
| `market-data-engine.*` | `MarketDataEngineConfig` | HFT cache engine refresh rates & TTLs |
| `strategy.*` | `StrategyConfig` | SL points, target points, hedge delta, trailing SL |
| `volatility.*` | `VolatilityConfig` | VIX filter, auto square-off time, daily P&L limits, trailing SL activation |
| `neutral-market.*` | `NeutralMarketConfig` | 5-signal scoring, VWAP/ADX/OI/range/premium thresholds |
| `kite.*` | `KiteConfig` | API key, secret, access token |
| `trading.*` | `PaperTradingConfig` | Paper mode toggle, charges, slippage, delays |
| `persistence.*` | `PersistenceConfig` | Data retention days, cleanup cron |
| `backtest.*` | — | Backtest async pool, candle interval, rate limit |
| `historical.replay.*` | — | Replay speed (ms/simulated-second) |

**Dev profile:** H2 file DB, `ddl-auto: update`, Flyway disabled, paper trading ON.  
**Prod profile** (`-prod`): PostgreSQL, `ddl-auto: validate`, Flyway enabled, paper trading OFF.

---

## 5. Data Flow — Strategy Execution

```
REST POST /strategy/execute (StrategyRequest)
    │
    ▼
StrategyController → StrategyService.executeStrategy()
    │
    ▼
StrategyFactory.create(StrategyType)  →  SellATMStraddleStrategy | ShortStrangleStrategy | ATMStraddleStrategy
    │
    ▼
[Pre-flight checks]
  1. DailyPnlGateService   — daily profit/loss limits not exceeded
  2. VolatilityFilterService — India VIX above threshold (VIX > 12.5 or 5m change > 0.3%)
  3. NeutralMarketDetectorService — composite score ≥ min-score (5 signals × 2pts each)
    │
    ▼
[Instrument resolution]
  MarketDataEngine.getPrecomputedStrikeByDelta(instrument, delta, optionType)
  MarketDataEngine.getOptionChain(instrument, expiry)
    │
    ▼
[Order placement] — parallel via CompletableFuture / HFT thread pool
  UnifiedTradingService → PaperTradingService (paper) | TradingService → Kite API (live)
    │
    ▼
[Position monitoring]
  PositionMonitorV2.onTick(Tick)
    → ExitStrategy[] evaluated in priority order:
        [0]   TimeBasedForcedExitStrategy   (auto square-off at volatility.auto-square-off-time)
        [50]  PremiumBasedExitStrategy       (% decay/expansion of combined premium)
        [100] PointsBasedExitStrategy        (fixed-point target)
        [300] TrailingStopLossStrategy       (dynamic trailing SL)
        [400] PointsBasedExitStrategy        (fixed-point stop loss)
    │
    ▼
[Exit triggered]
  StraddleExitHandler.executeExit()
  → UnifiedTradingService.placeBasketOrder()
  → TradePersistenceService.persistTrade() [async]
    │
    ▼
[Auto-restart]
  StrategyRestartScheduler listens for MarketStateEvent.NEUTRAL
  → waits neutral-market-buffer-ms → re-executes strategy
```

---

## 6. MarketDataEngine — HFT Cache Architecture

`MarketDataEngine` is the single most critical service. All strategy code reads from it; nothing calls Kite API inline during execution.

**Cache stores (all `ConcurrentHashMap<String, CacheEntry<T>>`):**

| Cache | Key format | Refresh | TTL |
|---|---|---|---|
| `spotPriceCache` | `"NIFTY"` | 1s | 2s |
| `optionChainCache` | `"NIFTY_WEEKLY"` | 60s | 120s |
| `atmStrikeCache` | `"NIFTY"` | 5s (delta cycle) | 10s |
| `deltaMapCache` | `"NIFTY"` | 5s | 10s |
| `strikeByDeltaCache` | `"NIFTY_0.1_CE"` | 5s | 10s |
| `vwapCache` | `"NIFTY"` | 5s | 10s |
| `candleCache` | `"NSE:NIFTY 50_minute"` | 60s | 120s |
| `nearestExpiryCache` | `"NIFTY"` | piggyback | 120s |

**Rule:** Strategy code must ONLY call `MarketDataEngine.get*()` methods — never `TradingService` directly for price/instrument lookups during execution. If engine is disabled or data stale, strategies fall back to inline API calls.

**Pre-computed delta targets:** `0.05, 0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5`  
Near-ATM scan range: ±10 strikes. Far-OTM (Δ < 0.3) scan range: ±30 strikes.

---

## 7. Neutral Market Detection (5-Signal Engine)

`NeutralMarketDetectorService` scores 5 signals. Each passing signal contributes +2 points. Minimum score to allow trade is configurable (`neutral-market.minimum-score`, default 6).

| Signal | Check | Threshold |
|---|---|---|
| VWAP Deviation | \|NIFTY price − VWAP\| / VWAP | ≤ 0.15% |
| ADX Trend Strength | ADX(14) on 3-min candles | < 18.0 |
| Gamma Pin (Max OI Strike) | Distance from max-OI strike | ≤ 0.2% |
| Range Compression | Last 5 × 1-min candle range | ≤ 0.25% |
| Dual Premium Decay | CE + PE LTP both declining vs snapshot | snapshot interval ≥ 25s |

Data flows: `MarketDataEngine` → detector → `MarketStateUpdater` → publishes `MarketStateEvent` every 30s → `StrategyRestartScheduler` acts on NEUTRAL events.

---

## 8. Exit Strategy Priorities (PositionMonitorV2)

```
Priority  Strategy                     Trigger
0         TimeBasedForcedExitStrategy  Clock >= auto-square-off-time (default 15:10)
50        PremiumBasedExitStrategy     Combined LTP decays/expands by target-decay-pct / stop-loss-expansion-pct
100       PointsBasedExitStrategy      Cumulative P&L >= target points (TARGET)
300       TrailingStopLossStrategy     P&L drops from high-water mark by trailing-distance-points
400       PointsBasedExitStrategy      Cumulative P&L <= -stop-loss-points (STOP_LOSS)
```

All `ExitStrategy` implementations must be **stateless** — all state is in `ExitContext`. Return `ExitResult.noExit()` (pre-allocated singleton) when no action. Avoid `new`, `BigDecimal`, or iterators on the hot path.

---

## 9. Coding Conventions

### Naming
- Services: `@Service`, suffix `Service` (e.g., `MarketDataEngine`, `VolatilityFilterService`)
- Controllers: `@RestController`, suffix `Controller`
- Configs: `@Configuration + @ConfigurationProperties`, suffix `Config`
- DTOs: plain suffix `Request` / `Response`
- Entities: suffix `Entity`
- Repositories: suffix `Repository`

### Patterns
- Always use `@Slf4j` (Lombok) for logging. Never `System.out.println`.
- Use `CurrentUserContext.get()` to access the current Kite session in multi-user flows.
- Return `ApiResponse<T>` from all controllers.
- Use `UnifiedTradingService` (not `TradingService` directly) for all order operations — it handles paper/live routing.
- Strategy entry: always go through `StrategyFactory` — never `new SellATMStraddleStrategy(...)`.
- For new exit logic, implement `ExitStrategy`, assign a priority, and register in `PositionMonitorV2`.
- Async persistence: use `TradePersistenceService` (async) — never block strategy threads on DB writes.

### HFT Rules (hot-path code)
- Prefer `double` over `Double` / `BigDecimal` for price arithmetic.
- Use `ThreadLocal<SimpleDateFormat>` — `SimpleDateFormat` is not thread-safe.
- Use pre-allocated result objects (e.g., `ExitResult.NO_EXIT_RESULT`) to avoid GC pressure.
- Use `ConcurrentHashMap` — never `synchronized` on the read path.
- Use indexed `for` loops — avoid `Iterator` allocations in per-tick code.

---

## 10. Strategies Reference

| StrategyType | Class | Legs | Entry Condition |
|---|---|---|---|
| `SELL_ATM_STRADDLE` | `SellATMStraddleStrategy` | Sell ATM CE + Sell ATM PE + optional 0.1Δ hedge legs | Neutral market score ≥ min-score AND VIX filter pass |
| `ATM_STRADDLE` | `ATMStraddleStrategy` | Buy ATM CE + Buy ATM PE | VIX filter |
| `SHORT_STRANGLE` | `ShortStrangleStrategy` | Sell 0.4Δ CE + Sell 0.4Δ PE + Buy 0.1Δ CE hedge + Buy 0.1Δ PE hedge | VIX filter |

Key config flags:
- `strategy.sell-straddle-hedge-enabled` — toggles hedge legs on `SELL_ATM_STRADDLE`
- `strategy.sell-straddle-hedge-delta` — delta for hedge legs (default 0.1)
- `volatility.premium-based-exit-enabled` — switches from fixed-point to % premium exit mode
- `volatility.trailing-stop-enabled` — activates `TrailingStopLossStrategy`
- `volatility.auto-restart-enabled` — enables neutral-market re-entry loop

---

## 11. Persistence Layer

All entities extend `BaseEntity` (implicit via JPA) with `@Id Long id`. Repositories are standard `JpaRepository<Entity, Long>`.

Async write path: `TradePersistenceService` → `PersistenceBufferService` (buffered batch writes) → DB via HikariCP.

DB migrations: Flyway SQL files in `src/main/resources/db/migration/` (prod only). Dev uses `ddl-auto: update`.

---

## 12. Testing Conventions

Tests live in `src/test/java/com/tradingbot/`. Use JUnit 5 + Mockito. Key test files:
- `PaperTradingServiceTest` — paper order simulation
- `PositionMonitorV2Test` — exit strategy evaluation
- `NeutralMarketDetectorServiceTest` — 5-signal scoring
- `DailyPnlGateServiceTest` — daily P&L halt logic
- `StrategyRestartSchedulerTest` — restart gating

Pattern: mock `MarketDataEngine`, `UnifiedTradingService`, `TradingService` via `@Mock`. Use `@InjectMocks` for the class under test.

---

## 13. Common Tasks & Where to Look

| Task | Files to read first |
|---|---|
| Add a new exit strategy | `ExitStrategy`, `AbstractExitStrategy`, `ExitContext`, `PositionMonitorV2` |
| Add a new trading strategy | `TradingStrategy`, `BaseStrategy`, `StrategyFactory`, `StrategyType` |
| Change neutral market signals | `NeutralMarketDetectorService`, `NeutralMarketConfig` |
| Modify order placement | `UnifiedTradingService`, `PaperTradingService`, `TradingService` |
| Add a new config property | Add field to relevant `*Config` class + `application.yml` |
| Add a new REST endpoint | Controller → Service → `ApiResponse<T>` |
| Add a new entity/table | `@Entity` class → `JpaRepository` → Flyway migration (prod) |
| Tune cache refresh | `MarketDataEngineConfig`, `application.yml` (`market-data-engine.*`) |
| Debug delta computation | `DeltaCacheService`, `MarketDataEngine` (delta refresh cycle), `BaseStrategy` |

---

## 14. Key Domain Vocabulary

| Term | Meaning |
|---|---|
| ATM | At-The-Money (strike closest to current NIFTY spot price) |
| CE / PE | Call option / Put option |
| Delta (Δ) | Option sensitivity to underlying price (0.5 = ATM, 0.1 = far OTM) |
| Straddle | Sell/buy CE + PE at same strike |
| Strangle | Sell CE + PE at different OTM strikes |
| Hedge leg | OTM buy option to cap max loss |
| VWAP | Volume-Weighted Average Price |
| MTM | Mark-to-Market (unrealised P&L) |
| IST | Indian Standard Time (Asia/Kolkata) — all timestamps must use this zone |
| NFO | NSE Futures & Options exchange segment |
| Lot size | NIFTY = 75 contracts per lot |
| Auto square-off | Forced exit of all positions at configured time (default 15:10 IST) |
| Neutral market | Low-volatility, range-bound market — optimal for straddle entry |
| MIS | Margin Intraday Settlement (intraday product type) |
