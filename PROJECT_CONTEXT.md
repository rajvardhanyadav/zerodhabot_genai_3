# PROJECT_CONTEXT.md — Zerodha Trading Bot

> **Purpose:** Comprehensive project reference for AI agents.
> All details needed to understand, modify, and extend this codebase are documented here.
> Last updated: 2026-03-20 | Version: 6.0

---

## Table of Contents

1. [Project Identity](#1-project-identity)
2. [Technology Stack](#2-technology-stack)
3. [Architecture Overview](#3-architecture-overview)
4. [Complete Package Structure](#4-complete-package-structure)
5. [Configuration System](#5-configuration-system)
6. [REST API Endpoints](#6-rest-api-endpoints)
7. [Strategy Execution Data Flow](#7-strategy-execution-data-flow)
8. [MarketDataEngine — HFT Cache Architecture](#8-marketdataengine--hft-cache-architecture)
9. [Trading Strategies](#9-trading-strategies)
10. [Exit Strategy System (PositionMonitorV2)](#10-exit-strategy-system-positionmonitorv2)
11. [Neutral Market Detection V1 (5-Signal Engine)](#11-neutral-market-detection-v1-5-signal-engine)
12. [Neutral Market Detection V2 (Weighted Confidence Engine)](#12-neutral-market-detection-v2-weighted-confidence-engine)
13. [Neutral Market Detection V3 (3-Layer Tradable Opportunity Detector)](#13-neutral-market-detection-v3-3-layer-tradable-opportunity-detector)
14. [Paper Trading Simulation](#14-paper-trading-simulation)
15. [Backtesting Engine](#15-backtesting-engine)
16. [Database Schema & Entities](#16-database-schema--entities)
17. [Persistence Layer](#17-persistence-layer)
18. [Multi-User Session Management](#18-multi-user-session-management)
19. [Key Interfaces & Contracts](#19-key-interfaces--contracts)
20. [Enums & Domain Model](#20-enums--domain-model)
21. [Deployment & Infrastructure](#21-deployment--infrastructure)
22. [Testing](#22-testing)
23. [Coding Conventions & HFT Rules](#23-coding-conventions--hft-rules)
24. [Common Tasks — Where to Look](#24-common-tasks--where-to-look)
25. [Domain Vocabulary](#25-domain-vocabulary)
26. [Changelog](#26-changelog)

---

## 1. Project Identity

| Property | Value |
|---|---|
| Name | `zerodha-trading-bot` |
| Artifact | `com.tradingbot:zerodhabot_genai_3:4.2` |
| Description | Production-grade intraday NIFTY 50 options trading bot for Indian derivatives market |
| Broker | Zerodha Kite Connect API |
| Strategies | Sell ATM Straddle, Short Strangle (with hedges), ATM Straddle |
| Modes | Live trading + Paper trading simulation |
| Architecture | HFT-oriented, sub-100ms execution latency target |
| Server Port | 8080 |

---

## 2. Technology Stack

### Core

| Layer | Technology | Version |
|---|---|---|
| Language | Java | 17 |
| Framework | Spring Boot | 3.2.0 |
| Build Tool | Apache Maven | (wrapper included: `mvnw` / `mvnw.cmd`) |
| Artifact Name | `zerodhabot_genai-exec.jar` | — |

### Dependencies (from `pom.xml`)

| Dependency | GroupId:ArtifactId | Version | Purpose |
|---|---|---|---|
| Spring Boot Web | `spring-boot-starter-web` | 3.2.0 (parent) | REST API layer |
| Spring Boot Validation | `spring-boot-starter-validation` | 3.2.0 | Request validation |
| Spring Boot Actuator | `spring-boot-starter-actuator` | 3.2.0 | Health/metrics endpoints |
| Spring Data JPA | `spring-boot-starter-data-jpa` | 3.2.0 | ORM + Hibernate 6 |
| Kite Connect SDK | `com.zerodhatech.kiteconnect:kiteconnect` | 3.5.1 | Zerodha broker API |
| Lombok | `org.projectlombok:lombok` | 1.18.30 | Boilerplate reduction (`@Slf4j`, `@Data`, etc.) |
| SpringDoc OpenAPI | `springdoc-openapi-starter-webmvc-ui` | 2.3.0 | Swagger UI at `/swagger-ui.html` |
| PostgreSQL Driver | `org.postgresql:postgresql` | (managed) | Production database |
| H2 Database | `com.h2database:h2` | (managed) | Development/test database |
| Flyway Core | `org.flywaydb:flyway-core` | (managed) | Database migrations |
| Flyway PostgreSQL | `org.flywaydb:flyway-database-postgresql` | 10.10.0 | PostgreSQL-specific migrations |
| Eclipse Collections | `org.eclipse.collections:eclipse-collections` | 11.1.0 | HFT-optimized primitive collections (avoids autoboxing) |
| Spring Boot Test | `spring-boot-starter-test` | 3.2.0 | JUnit 5 + Mockito |

### Runtime Infrastructure

| Component | Dev | Prod |
|---|---|---|
| Database | H2 File (`./data/tradingbot`) | PostgreSQL |
| DDL Management | `ddl-auto: update` | `ddl-auto: validate` + Flyway |
| Flyway | Disabled | Enabled |
| Connection Pool | HikariCP (pool: 15, min-idle: 5) | HikariCP (pool: 20, min-idle: 5) |
| Trading Mode | Paper trading ON | Paper trading OFF (live) |
| Logging Level | DEBUG | INFO |
| H2 Console | Enabled (`/h2-console`) | Disabled |
| Container | — | Docker (Eclipse Temurin 17 JDK Alpine) |
| Orchestration | — | Kubernetes (Deployment + NodePort Service) |

---

## 3. Architecture Overview

### Three-Tier Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  REST API Layer (Controllers)                                │
│  ┌───────────┬──────────────┬──────────────┬──────────────┐  │
│  │ Strategy  │ MarketData   │ Order        │ Portfolio    │  │
│  │ Controller│ Controller   │ Controller   │ Controller   │  │
│  └─────┬─────┴──────┬───────┴──────┬───────┴──────┬───────┘  │
├────────┼────────────┼──────────────┼──────────────┼──────────┤
│  Service Layer (Business Logic)                              │
│  ┌─────┴────────────┴──────────────┴──────────────┴───────┐  │
│  │              UnifiedTradingService                      │  │
│  │         (Routes: Paper vs Live trading)                 │  │
│  │    ┌──────────────┐    ┌──────────────────────┐        │  │
│  │    │PaperTrading  │    │   TradingService     │        │  │
│  │    │Service       │    │  (Kite API Wrapper)  │        │  │
│  │    └──────────────┘    └──────────────────────┘        │  │
│  │                                                         │  │
│  │  ┌─────────────────────────────────────────────────┐   │  │
│  │  │         MarketDataEngine (HFT Cache)            │   │  │
│  │  │   Spot | OptionChain | Delta | VWAP | Candle    │   │  │
│  │  └─────────────────────────────────────────────────┘   │  │
│  │                                                         │  │
│  │  ┌─────────────┐  ┌──────────────┐  ┌──────────────┐  │  │
│  │  │ Strategy    │  │PositionMoni- │  │ Neutral      │  │  │
│  │  │ Service     │  │torV2 (Exit)  │  │ MarketDet.   │  │  │
│  │  └─────────────┘  └──────────────┘  └──────────────┘  │  │
│  └─────────────────────────────────────────────────────────┘  │
├───────────────────────────────────────────────────────────────┤
│  Persistence Layer                                            │
│  ┌──────────────────┐  ┌──────────────┐  ┌───────────────┐   │
│  │TradePersistence  │  │EndOfDayPer-  │  │DataCleanup    │   │
│  │Service (Async)   │  │sistenceServ. │  │Service        │   │
│  └──────────┬───────┘  └──────┬───────┘  └──────┬────────┘   │
│             │                  │                  │            │
│  ┌──────────┴──────────────────┴──────────────────┴────────┐  │
│  │  Spring Data JPA Repositories → H2 (dev) / Postgres    │  │
│  └─────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────┘
```

### Key Architectural Decisions

1. **HFT Cache-First Design:** All market data is pre-computed by `MarketDataEngine` on background threads. Strategy execution reads from `ConcurrentHashMap` caches only — never makes inline Kite API calls.
2. **Paper/Live Routing:** `UnifiedTradingService` is the single entry point for all order operations. It transparently routes to `PaperTradingService` or `TradingService` based on `trading.paper-trading-enabled`.
3. **Strategy Pattern for Exits:** `PositionMonitorV2` evaluates a priority-ordered chain of `ExitStrategy` implementations on every tick. Each strategy is stateless; all state flows through `ExitContext`.
4. **Event-Driven Restart:** `MarketStateUpdater` publishes `MarketStateEvent` records; `StrategyRestartScheduler` listens and triggers buffered re-entry — no polling loops.
5. **Async Persistence:** All trade/order writes go through `TradePersistenceService` → `PersistenceBufferService` (buffered batch writes) to avoid blocking strategy threads on DB I/O.
6. **Multi-User Support:** `UserSessionManager` manages per-user Kite sessions with DB-backed recovery (for Cloud Run container restarts). `CurrentUserContext` (ThreadLocal) provides the active user to any service.

---

## 4. Complete Package Structure

```
com.tradingbot
├── TradingBotApplication.java                    # Spring Boot main class

├── config/                                        # @Configuration / @ConfigurationProperties
│   ├── AsyncPersistenceConfig.java               # Async thread pool for persistence (HFTSafeRejectionHandler, UserContextPropagatingTaskDecorator)
│   ├── CorsConfig.java                           # CORS configuration
│   ├── KiteConfig.java                           # kite.* properties (API key, secret, access token)
│   ├── MarketDataEngineConfig.java               # market-data-engine.* properties
│   ├── NeutralMarketConfig.java                  # neutral-market.* properties (5-signal thresholds)
│   ├── NeutralMarketV3Config.java                # neutral-market-v3.* properties (3-layer: regime, micro, breakout)
│   ├── PaperTradingConfig.java                   # trading.* properties (paper mode, charges, slippage)
│   ├── PersistenceConfig.java                    # persistence.* (retention, cleanup cron) + nested RetentionConfig, CleanupConfig
│   ├── StrategyConfig.java                       # strategy.* (SL, target, hedge, entry windows, trailing SL)
│   ├── SwaggerConfig.java                        # SpringDoc OpenAPI configuration
│   ├── SwaggerGlobalHeaderConfig.java            # Global header parameter injection for Swagger
│   ├── UserContextFilter.java                    # Servlet filter: sets MDC / CurrentUserContext per request
│   └── VolatilityConfig.java                     # volatility.* (VIX filter, auto square-off, daily P&L limits, premium-based exit, trailing SL)

├── controller/                                    # REST controllers (@RestController)
│   ├── AccountController.java                    # /api/account — margins
│   ├── AuthController.java                       # /api/auth — login URL, session, profile, logout
│   ├── GTTController.java                        # /api/gtt — Good-Till-Triggered order CRUD
│   ├── HealthController.java                     # /api/health — system health + session status
│   ├── MarketDataController.java                 # /api/market — quotes, OHLC, LTP, historical, instruments, engine status
│   ├── MonitoringController.java                 # /api/monitoring — WebSocket, delta cache, rate limiter, persistence buffer, system health
│   ├── OrderController.java                      # /api/orders — place, modify, cancel, list, history, charges
│   ├── PaperTradingController.java               # /api/paper-trading — status, account, reset, statistics, mode toggle
│   ├── PortfolioController.java                  # /api/portfolio — positions, holdings, trades, position convert, day P&L
│   ├── StrategyController.java                   # /api/strategies — execute, stop, stop-all, active, types, instruments, expiries, bot-status
│   └── TradingHistoryController.java             # /api/history — trades, strategies, daily summary, position snapshots, alerts, MTM

├── service/                                       # Core business logic
│   ├── BotStatusService.java                     # Bot start/stop/status tracking
│   ├── InstrumentCacheService.java               # NFO instrument token lookup cache
│   ├── LogoutService.java                        # Session cleanup on logout
│   ├── MarketDataEngine.java                     # ⭐ Central HFT cache engine (see §8)
│   ├── RateLimiterService.java                   # Kite API rate limiting (prevents 429s)
│   ├── StrategyService.java                      # Strategy lifecycle management (execute, stop, status tracking)
│   ├── TradingConstants.java                     # String constants (exchanges, order types, products)
│   ├── TradingService.java                       # Direct Kite API wrapper (live orders, quotes, instruments)
│   ├── UnifiedTradingService.java                # ⭐ Routes: Paper vs Live based on config toggle
│   │
│   ├── greeks/
│   │   └── DeltaCacheService.java                # Black-Scholes delta pre-computation cache
│   │
│   ├── session/
│   │   └── UserSessionManager.java               # Multi-user Kite session management + DB-backed recovery
│   │
│   ├── strategy/                                  # Strategy implementations & support
│   │   ├── TradingStrategy.java                  # Interface: execute(), getName(), getType()
│   │   ├── BaseStrategy.java                     # Abstract base: common instrument/order/delta utilities
│   │   ├── SellATMStraddleStrategy.java          # ⭐ Primary: Sell ATM CE + PE + optional hedge legs
│   │   ├── ATMStraddleStrategy.java              # Buy ATM CE + PE
│   │   ├── ShortStrangleStrategy.java            # Sell 0.4Δ CE/PE + Buy 0.1Δ hedge CE/PE
│   │   ├── StrategyFactory.java                  # Creates strategy instances by StrategyType enum
│   │   ├── StrategyCompletionCallback.java       # Callback interface for strategy lifecycle events
│   │   ├── StraddleExitHandler.java              # Handles exit order execution
│   │   ├── LegReplacementHandler.java            # Roll/replace individual legs
│   │   ├── MonitoringSetupHelper.java            # Wires up PositionMonitorV2 after order placement
│   │   ├── MarketStateUpdater.java               # Publishes MarketStateEvent (neutral/trending) on a timer
│   │   ├── StrategyRestartScheduler.java         # Listens for neutral market events → triggers re-entry
│   │   ├── DailyPnlGateService.java              # Halts restart if daily P&L limit hit
│   │   ├── NeutralMarketDetectorService.java     # V1: 5-signal neutral market scoring engine (binary +2 per signal)
│   │   ├── NeutralMarketDetectorServiceV2.java   # ⭐ V2: Weighted confidence scoring engine (6 signals, regime classification) (see §12)
│   │   ├── NeutralMarketDetectorServiceV3.java   # ⭐ V3: 3-Layer tradable opportunity detector (regime + micro + breakout) (see §13)
│   │   └── VolatilityFilterService.java          # India VIX filter before strategy entry
│   │
│   │   └── monitoring/
│   │       ├── WebSocketService.java             # Kite WebSocket tick subscription management
│   │       ├── PositionMonitorV2.java            # ⭐ HFT: Strategy-pattern exit evaluation on every tick
│   │       ├── LegMonitor.java                   # Individual option leg tracking
│   │       └── exit/                              # Exit strategy implementations
│   │           ├── ExitStrategy.java             # Interface: getPriority(), evaluate(ExitContext), getName()
│   │           ├── ExitContext.java               # Shared state object passed to all exit strategies
│   │           ├── ExitResult.java               # Result: ExitType enum, reason, isStopLoss (pre-allocated NO_EXIT singleton)
│   │           ├── AbstractExitStrategy.java     # Base class with common helper methods
│   │           ├── PointsBasedExitStrategy.java  # Fixed-point target (priority 100) + SL (priority 400)
│   │           ├── PremiumBasedExitStrategy.java  # % premium decay/expansion (priority 50)
│   │           ├── TimeBasedForcedExitStrategy.java # Auto square-off at configured time (priority 0)
│   │           ├── TrailingStopLossStrategy.java  # Dynamic trailing SL from high-water mark (priority 300)
│   │           └── package-info.java
│   │
│   └── persistence/
│       ├── TradePersistenceService.java           # Async trade/order write-through to DB
│       ├── PersistenceBufferService.java          # Buffer + batch DB writes
│       ├── EndOfDayPersistenceService.java        # EOD P&L summary aggregation
│       ├── SystemHealthMonitorService.java        # Periodic health snapshots to DB
│       └── DataCleanupService.java                # Scheduled old-data pruning (cron: 2 AM daily)

├── paper/                                         # Paper trading simulation
│   ├── PaperTradingService.java                  # In-memory order/position/P&L simulation
│   ├── PaperAccount.java                         # Virtual account state (balance, positions)
│   ├── PaperOrder.java                           # Simulated order model
│   ├── PaperPosition.java                        # Simulated position model
│   ├── ZerodhaChargeCalculator.java              # Realistic brokerage/STT/GST charge computation
│   ├── entity/
│   │   └── OrderCharges.java                     # Charge breakdown value object
│   └── repository/                                # (empty — paper data is in-memory only)

├── backtest/                                      # Backtesting engine
│   ├── adapter/
│   │   ├── HistoricalCandleAdapter.java          # Adapts Kite historical candles to tick feed
│   │   └── TickFeedMerger.java                   # Merges multiple tick feeds chronologically
│   ├── config/
│   │   └── BacktestConfig.java                   # Backtest async pool and cache configuration
│   ├── controller/
│   │   └── BacktestController.java               # /api/backtest — run, batch, async, results, cache
│   ├── dto/
│   │   ├── BacktestRequest.java                  # Backtest run parameters
│   │   ├── BacktestResult.java                   # Backtest output with trades and metrics
│   │   └── BacktestTrade.java                    # Individual trade in backtest results
│   ├── engine/
│   │   ├── BacktestEngine.java                   # Historical replay engine
│   │   ├── BacktestException.java                # Custom exception
│   │   ├── HistoricalDataFetcher.java            # Kite historical candle API client
│   │   └── InstrumentResolver.java               # Resolves instruments for historical dates
│   └── service/
│       └── BacktestService.java                  # Orchestrates backtest execution

├── entity/                                        # JPA entities (@Entity)
│   ├── TradeEntity.java                          # Individual trade executions
│   ├── StrategyExecutionEntity.java              # Strategy execution lifecycle
│   ├── StrategyConfigHistoryEntity.java          # Strategy config snapshots
│   ├── OrderLegEntity.java                       # Individual order legs within strategy
│   ├── OrderTimingEntity.java                    # HFT latency metrics per order
│   ├── DeltaSnapshotEntity.java                  # Greeks/delta snapshots
│   ├── MTMSnapshotEntity.java                    # Mark-to-market snapshots
│   ├── PositionSnapshotEntity.java               # EOD position snapshots
│   ├── DailyPnLSummaryEntity.java                # Aggregated daily P&L
│   ├── AlertHistoryEntity.java                   # Alert/notification history
│   ├── WebSocketEventEntity.java                 # WebSocket connection events
│   ├── SystemHealthSnapshotEntity.java           # Periodic system health data
│   └── UserSessionEntity.java                    # Kite session persistence for Cloud Run recovery

├── repository/                                    # Spring Data JPA repositories
│   ├── TradeRepository.java
│   ├── StrategyExecutionRepository.java
│   ├── StrategyConfigHistoryRepository.java
│   ├── OrderLegRepository.java
│   ├── OrderTimingRepository.java
│   ├── DeltaSnapshotRepository.java
│   ├── MTMSnapshotRepository.java
│   ├── PositionSnapshotRepository.java
│   ├── DailyPnLSummaryRepository.java
│   ├── AlertHistoryRepository.java
│   ├── WebSocketEventRepository.java
│   ├── SystemHealthSnapshotRepository.java
│   └── UserSessionRepository.java

├── dto/                                           # Request/response DTOs
│   ├── ApiResponse.java                          # Standard wrapper: {success: boolean, message: string, data: T}
│   ├── StrategyRequest.java                      # Strategy execution parameters
│   ├── StrategyExecutionResponse.java            # Strategy execution result
│   ├── StrategyTypeInfo.java                     # Strategy type metadata
│   ├── OrderRequest.java                         # Order placement parameters
│   ├── OrderResponse.java                        # Order result
│   ├── OrderChargesResponse.java                 # Charge breakdown
│   ├── BasketOrderRequest.java                   # Multi-leg basket order
│   ├── BasketOrderResponse.java                  # Basket order result
│   ├── LoginRequest.java                         # Auth request
│   ├── LogoutResponse.java                       # Auth response
│   ├── DayPnLResponse.java                       # Daily P&L data
│   ├── BotStatusResponse.java                    # Bot state info
│   └── InstrumentInfo.java                       # Instrument metadata

├── model/                                         # Domain enums & value objects
│   ├── StrategyType.java                         # ATM_STRADDLE, SELL_ATM_STRADDLE, SHORT_STRANGLE
│   ├── StrategyStatus.java                       # PENDING, EXECUTING, ACTIVE, COMPLETED, FAILED, SKIPPED
│   ├── StrategyCompletionReason.java             # TARGET_HIT, STOPLOSS_HIT, MANUAL_STOP, TIME_BASED_EXIT, ERROR, DAY_PROFIT_LIMIT_HIT, DAY_LOSS_LIMIT_HIT, OTHER
│   ├── SlTargetMode.java                         # POINTS, PREMIUM, MTM
│   ├── BotStatus.java                            # Bot lifecycle state
│   ├── StrategyExecution.java                    # Runtime execution state model
│   ├── MarketStateEvent.java                     # Record: instrumentType, neutral, score, maxScore, result, evaluatedAt
│   ├── NeutralMarketResult.java                  # V2 result: score (0–10), confidence, regime (STRONG_NEUTRAL/WEAK_NEUTRAL/TRENDING), tradable flag, signal breakdown
│   ├── NeutralMarketResultV3.java                # V3 result: regime + micro + breakout composite (immutable, pre-allocated disabled singleton)
│   ├── SignalResult.java                         # V2 signal: record with name, score, maxScore, passed, detail (factory methods: passed/failed/unavailable/partial)
│   ├── Regime.java                               # V3 regime classification: STRONG_NEUTRAL, WEAK_NEUTRAL, TRENDING
│   └── BreakoutRisk.java                         # V3 breakout risk: LOW, MEDIUM, HIGH

├── util/
│   ├── ApiConstants.java                         # API path constants
│   ├── TradingConstants.java                     # Exchange/order/product type strings (NSE, NFO, MIS, LIMIT, etc.)
│   ├── StrategyConstants.java                    # Strategy-specific constants
│   ├── CandleUtils.java                          # OHLCV candle computation helpers
│   └── CurrentUserContext.java                   # ThreadLocal<String> for current user ID

└── exception/
    └── GlobalExceptionHandler.java               # @ControllerAdvice — maps exceptions to ApiResponse
```

---

## 5. Configuration System

All behavior is driven by `application.yml` (dev) and `application-prod.yml` (prod). Key configuration prefixes:

### 5.1 Kite Connect (`kite.*` → `KiteConfig`)

| Property | Default (Dev) | Description |
|---|---|---|
| `kite.api-key` | `${KITE_API_KEY}` | Kite Connect API key |
| `kite.api-secret` | `${KITE_API_SECRET}` | Kite Connect API secret |
| `kite.access-token` | `${KITE_ACCESS_TOKEN:}` | Session access token |
| `kite.login-url` | `https://kite.zerodha.com/connect/login` | OAuth login URL |

### 5.2 Paper Trading (`trading.*` → `PaperTradingConfig`)

| Property | Default | Description |
|---|---|---|
| `trading.paper-trading-enabled` | `true` (dev) / `false` (prod) | Master paper/live switch |
| `trading.initial-balance` | `1000000.0` | Virtual balance (INR) |
| `trading.apply-brokerage-charges` | `true` | Simulate realistic charges |
| `trading.brokerage-per-order` | `20.0` | Brokerage per order (INR) |
| `trading.stt-percentage` | `0.025` | STT percentage |
| `trading.transaction-charges` | `0.00325` | Exchange transaction charges |
| `trading.gst-percentage` | `18.0` | GST on brokerage |
| `trading.sebi-charges` | `0.0001` | SEBI turnover charges |
| `trading.stamp-duty` | `0.003` | Stamp duty |
| `trading.slippage-percentage` | `0.05` | Simulated slippage |
| `trading.enable-execution-delay` | `true` (dev) / `false` (prod) | Simulate network delay |
| `trading.execution-delay-ms` | `500` (dev) / `100` (prod) | Delay in milliseconds |
| `trading.enable-order-rejection` | `false` | Simulate random rejections |
| `trading.rejection-probability` | `0.02` | Rejection probability |

### 5.3 Strategy (`strategy.*` → `StrategyConfig`)

| Property | Default | Description |
|---|---|---|
| `strategy.default-stop-loss-points` | `2.0` | Default SL in points |
| `strategy.default-target-points` | `2.0` | Default target in points |
| `strategy.short-strangle-sell-delta` | `0.4` | Sell leg delta for short strangle |
| `strategy.short-strangle-hedge-delta` | `0.1` | Hedge leg delta for short strangle |
| `strategy.sell-straddle-hedge-enabled` | `true` | Enable hedge legs on SELL_ATM_STRADDLE |
| `strategy.sell-straddle-hedge-delta` | `0.1` | Hedge leg delta |
| `strategy.entry-window-start` | `"10:00"` | Earliest entry time (IST) |
| `strategy.entry-window-end` | `"15:00"` | Latest entry time (IST) |
| `strategy.expiry-day-enabled` | `true` | Allow trading on expiry day |
| `strategy.expiry-day-entry-end-time` | `"13:00"` | Tighter cutoff on expiry |
| `strategy.trailing-stop-enabled` | `false` | Enable trailing SL mode |

### 5.4 Volatility (`volatility.*` → `VolatilityConfig`)

| Property | Default | Description |
|---|---|---|
| `volatility.enabled` | `true` | VIX filter master switch |
| `volatility.vix-symbol` | `"NSE:INDIA VIX"` | VIX symbol in Kite format |
| `volatility.vix-instrument-token` | `"264969"` | VIX instrument token |
| `volatility.absolute-threshold` | `12.5` | Minimum VIX for entry |
| `volatility.spike-threshold-pct` | `1.0` | Block if VIX 5-min change > this % |
| `volatility.max-vix-above-prev-close-pct` | `5.0` | Block if VIX too far above prev close |
| `volatility.cache-ttl-ms` | `60000` | VIX data cache TTL |
| `volatility.trailing-activation-points` | `2.5` | Profit points before trailing SL activates |
| `volatility.trailing-distance-points` | `2.5` | Distance below high-water mark |
| `volatility.auto-square-off-enabled` | `true` | Force exit at configured time |
| `volatility.auto-square-off-time` | `"15:10"` (dev) / `"15:15"` (prod) | Auto square-off time (IST) |
| `volatility.auto-restart-enabled` | `true` | Enable neutral market re-entry loop |
| `volatility.neutral-market-poll-interval-ms` | `30000` | Market state evaluation cycle |
| `volatility.neutral-market-buffer-ms` | `60000` | Wait after neutral detected before re-entry |
| `volatility.daily-max-profit` | `2500` | Max daily profit (INR) before halt |
| `volatility.daily-max-loss` | `2500` | Max daily loss (INR) before halt |
| `volatility.premium-based-exit-enabled` | `false` | Switch to % premium exit mode |
| `volatility.target-decay-pct` | `5` | Target: exit when premium decays 5% |
| `volatility.stop-loss-expansion-pct` | `10` | SL: exit when premium expands 10% |

### 5.5 Neutral Market Detection (`neutral-market.*` → `NeutralMarketConfig`)

| Property | Default | Description |
|---|---|---|
| `neutral-market.enabled` | `true` | Master switch |
| `neutral-market.minimum-score` | `6` (dev) / `7` (prod) | Min score to allow trade (0-10) |
| `neutral-market.expiry-day-minimum-score` | `8` | Tighter threshold on expiry |
| `neutral-market.vwap-deviation-threshold` | `0.002` | VWAP signal: max 0.2% deviation |
| `neutral-market.adx-threshold` | `20.0` | ADX signal: below 20 = ranging |
| `neutral-market.adx-period` | `7` | ADX calculation period |
| `neutral-market.adx-candle-interval` | `"minute"` | ADX candle interval |
| `neutral-market.adx-candle-count` | `30` | ADX warmup candles |
| `neutral-market.gamma-pin-threshold` | `0.002` | Max distance from max-OI strike |
| `neutral-market.strikes-around-atm` | `5` | OI scan range |
| `neutral-market.range-compression-threshold` | `0.0035` | Max range of last N candles |
| `neutral-market.range-compression-candles` | `10` | Number of candles for range check |
| `neutral-market.premium-snapshot-min-interval-ms` | `20000` | Premium decay snapshot interval |
| `neutral-market.premium-min-decay-pct` | `0.5` | Min decay % for signal pass |
| `neutral-market.cache-ttl-ms` | `30000` | Result cache TTL |

#### V2 Weighted Scoring Properties (since 5.0)

| Property | Default | Description |
|---|---|---|
| `neutral-market.oscillation-candle-count` | `10` | Candles for price oscillation (chop) detection |
| `neutral-market.oscillation-min-reversals` | `4` | Min direction reversals to confirm chop |
| `neutral-market.vwap-pullback-threshold` | `0.003` | Max deviation from VWAP for pullback zone (0.3%) |
| `neutral-market.vwap-pullback-reversion-threshold` | `0.001` | Max deviation qualifying as "reverted" (0.1%) |
| `neutral-market.vwap-pullback-candle-count` | `8` | Candles for VWAP pullback pattern |
| `neutral-market.time-based-adaptation-enabled` | `true` | Enable time-of-day score adjustment |
| `neutral-market.weight-vwap` | `3` | Signal weight: VWAP Proximity |
| `neutral-market.weight-range` | `2` | Signal weight: Range Compression |
| `neutral-market.weight-oscillation` | `2` | Signal weight: Price Oscillation |
| `neutral-market.weight-vwap-pullback` | `2` | Signal weight: VWAP Pullback |
| `neutral-market.weight-adx` | `1` | Signal weight: ADX Trend |
| `neutral-market.weight-gamma-pin` | `1` | Signal weight: Gamma Pin (expiry only) |
| `neutral-market.strong-neutral-threshold` | `6` | Min score for STRONG_NEUTRAL regime |
| `neutral-market.weak-neutral-threshold` | `4` | Min score for WEAK_NEUTRAL regime (tradable) |

#### V3 3-Layer Detection Properties (`neutral-market-v3.*` → `NeutralMarketV3Config`, since 6.0)

| Property | Default | Description |
|---|---|---|
| `neutral-market-v3.enabled` | `true` | Master switch for V3 detector |
| `neutral-market-v3.allow-on-data-unavailable` | `true` | Fail-safe: allow trade when data unavailable |
| `neutral-market-v3.cache-ttl-ms` | `15000` | Composite result cache TTL (15s — fast refresh for micro signals) |
| **Regime Weights** | | |
| `neutral-market-v3.weight-vwap-proximity` | `3` | VWAP proximity weight (highest — core mean-reversion) |
| `neutral-market-v3.weight-range-compression` | `2` | Range compression weight |
| `neutral-market-v3.weight-oscillation` | `2` | Price oscillation weight |
| `neutral-market-v3.weight-adx` | `1` | ADX trend weight (confirmatory, lagging) |
| `neutral-market-v3.weight-gamma-pin` | `1` | Gamma pin weight (expiry day only) |
| **Regime Thresholds** | | |
| `neutral-market-v3.vwap-proximity-threshold` | `0.002` | Max VWAP deviation (0.2%) |
| `neutral-market-v3.vwap-candle-count` | `15` | Candles for VWAP SMA proxy |
| `neutral-market-v3.range-compression-threshold` | `0.0035` | Max range fraction (0.35%) |
| `neutral-market-v3.range-compression-candles` | `10` | Candles for range check |
| `neutral-market-v3.oscillation-candle-count` | `10` | Candles for oscillation detection |
| `neutral-market-v3.oscillation-min-reversals` | `4` | Min direction reversals |
| `neutral-market-v3.adx-threshold` | `20.0` | ADX < this = ranging |
| `neutral-market-v3.adx-period` | `7` | Wilder smoothing period |
| `neutral-market-v3.adx-candle-interval` | `"minute"` | ADX candle interval |
| `neutral-market-v3.adx-candle-count` | `30` | ADX warmup candles |
| `neutral-market-v3.gamma-pin-threshold` | `0.002` | Max distance from max-OI strike (0.2%) |
| `neutral-market-v3.strikes-around-atm` | `5` | Strikes each side of ATM for OI scan |
| **Regime Classification** | | |
| `neutral-market-v3.regime-strong-neutral-threshold` | `6` | Score ≥ 6 → STRONG_NEUTRAL |
| `neutral-market-v3.regime-weak-neutral-threshold` | `4` | Score ≥ 4 → WEAK_NEUTRAL |
| `neutral-market-v3.micro-neutral-override-regime-threshold` | `3` | Min regime for micro-neutral override |
| `neutral-market-v3.micro-neutral-override-micro-threshold` | `3` | Min micro score for micro-neutral override |
| **Microstructure Weights** | | |
| `neutral-market-v3.weight-micro-vwap-pullback` | `2` | VWAP pullback momentum weight |
| `neutral-market-v3.weight-micro-oscillation` | `2` | HF oscillation weight |
| `neutral-market-v3.weight-micro-range-stability` | `1` | Micro range stability weight |
| **Microstructure Thresholds** | | |
| `neutral-market-v3.micro-vwap-pullback-deviation-threshold` | `0.0015` | Min VWAP deviation to qualify as "away" (0.15%) |
| `neutral-market-v3.micro-vwap-pullback-candles` | `5` | Window for pullback detection |
| `neutral-market-v3.micro-vwap-pullback-slope-candles` | `3` | Slope window for reversion check |
| `neutral-market-v3.micro-oscillation-candles` | `8` | Shorter window than regime (immediacy) |
| `neutral-market-v3.micro-oscillation-min-flips` | `4` | Min direction flips |
| `neutral-market-v3.micro-oscillation-max-avg-move` | `0.001` | Max avg move per candle (0.1%) |
| `neutral-market-v3.micro-range-candles` | `5` | Candles for micro range stability |
| `neutral-market-v3.micro-range-threshold` | `0.001` | Max micro range fraction (0.1%) |
| **Breakout Risk** | | |
| `neutral-market-v3.breakout-tight-range-threshold` | `0.0015` | Tight consolidation threshold (0.15%) |
| `neutral-market-v3.breakout-range-candles` | `10` | Candles for breakout range analysis |
| `neutral-market-v3.breakout-edge-proximity-pct` | `0.2` | Within 20% of range from edge |
| `neutral-market-v3.breakout-momentum-candles` | `3` | Consecutive same-direction candles |
| **Time Adaptation** | | |
| `neutral-market-v3.time-based-adaptation-enabled` | `true` | 09:15–10:00 → −1, 13:30–15:00 → +1 |

### 5.6 Market Data Engine (`market-data-engine.*` → `MarketDataEngineConfig`)

| Property | Default | Description |
|---|---|---|
| `market-data-engine.enabled` | `true` | Master switch |
| `market-data-engine.spot-price-refresh-ms` | `1000` | Spot price refresh (1s) |
| `market-data-engine.option-chain-refresh-ms` | `60000` | Option chain refresh (60s) |
| `market-data-engine.delta-refresh-ms` | `5000` | Delta computation refresh (5s) |
| `market-data-engine.vwap-refresh-ms` | `5000` | VWAP refresh (5s) |
| `market-data-engine.candle-refresh-ms` | `60000` | Candle data refresh (60s) |
| `market-data-engine.spot-price-ttl-ms` | `2000` | Spot cache freshness (2s) |
| `market-data-engine.option-chain-ttl-ms` | `120000` | Option chain freshness (2m) |
| `market-data-engine.delta-ttl-ms` | `10000` | Delta freshness (10s) |
| `market-data-engine.vwap-ttl-ms` | `10000` | VWAP freshness (10s) |
| `market-data-engine.candle-ttl-ms` | `120000` | Candle freshness (2m) |
| `market-data-engine.thread-pool-size` | `4` | Background refresh threads |
| `market-data-engine.supported-instruments` | `NIFTY` | Instruments to track |
| `market-data-engine.delta-targets` | `"0.05,0.1,...,0.5"` | Pre-computed delta values |
| `market-data-engine.delta-strike-range-near-atm` | `10` | Strike scan for Δ ≥ 0.3 |
| `market-data-engine.delta-strike-range-far-otm` | `30` | Strike scan for Δ < 0.3 |

### 5.7 Persistence (`persistence.*` → `PersistenceConfig`)

| Property | Default (Dev) | Prod | Description |
|---|---|---|---|
| `persistence.enabled` | `true` | `true` | Global persistence switch |
| `persistence.retention.trades-days` | `365` | `730` | Trade retention |
| `persistence.retention.delta-snapshots-days` | `90` | `180` | Delta snapshots retention |
| `persistence.retention.position-snapshots-days` | `180` | `365` | Position snapshots retention |
| `persistence.retention.order-timing-days` | `90` | `180` | Order timing retention |
| `persistence.cleanup.enabled` | `true` | `true` | Auto-cleanup |
| `persistence.cleanup.cron` | `"0 0 2 * * ?"` | `"0 0 2 * * ?"` | 2 AM daily |

### 5.8 Backtest (`backtest.*`)

| Property | Default | Description |
|---|---|---|
| `backtest.enabled` | `true` | Enable backtest module |
| `backtest.max-cache-size` | `100` | Max cached backtest results |
| `backtest.async-pool-size` | `4` | Async execution pool |
| `backtest.default-candle-interval` | `minute` | Default historical candle interval |
| `backtest.rate-limit-delay-ms` | `350` | Delay between Kite API calls |

### 5.9 Profile Differences Summary

| Aspect | Dev (default) | Prod (`-prod`) |
|---|---|---|
| Database | H2 file `./data/tradingbot` | PostgreSQL via env vars |
| DDL | `ddl-auto: update` | `ddl-auto: validate` |
| Flyway | Disabled | Enabled |
| HikariCP Pool | 15 max | 20 max |
| Trading Mode | Paper | Live |
| Logging | DEBUG | INFO |
| H2 Console | `/h2-console` | Disabled |
| Neutral Market Min Score | 6 | 7 |
| Auto Square-off | 15:10 | 15:15 |

---

## 6. REST API Endpoints

All endpoints return `ApiResponse<T>` wrapper: `{ success: boolean, message: string, data: T }`.

### 6.1 Authentication (`/api/auth` — `AuthController`)

| Method | Path | Description |
|---|---|---|
| GET | `/api/auth/login-url` | Get Kite OAuth login URL |
| POST | `/api/auth/session` | Create session with request token |
| GET | `/api/auth/profile` | Get current user profile |
| POST | `/api/auth/logout` | Terminate session |

### 6.2 Strategy Execution (`/api/strategies` — `StrategyController`)

| Method | Path | Description |
|---|---|---|
| POST | `/api/strategies/execute` | Execute a trading strategy |
| GET | `/api/strategies/active` | Get all active strategy executions |
| GET | `/api/strategies/{executionId}` | Get specific execution details |
| GET | `/api/strategies/types` | List available strategy types |
| GET | `/api/strategies/instruments` | List tradeable instruments |
| GET | `/api/strategies/expiries/{instrumentType}` | Get available expiry dates |
| POST | `/api/strategies/stop/{executionId}` | Stop specific execution |
| DELETE | `/api/strategies/stop-all` | Stop all active strategies |
| GET | `/api/strategies/bot-status` | Get overall bot status |

### 6.3 Market Data (`/api/market` — `MarketDataController`)

| Method | Path | Description |
|---|---|---|
| GET | `/api/market/engine/status` | MarketDataEngine status & cache health |
| GET | `/api/market/engine/spot/{instrumentType}` | Pre-cached spot price |
| GET | `/api/market/engine/atm-strike/{instrumentType}` | Pre-cached ATM strike |
| GET | `/api/market/quote` | Full quote(s) from Kite |
| GET | `/api/market/ohlc` | OHLC data |
| GET | `/api/market/ltp` | Last traded price |
| GET | `/api/market/historical` | Historical candle data |
| GET | `/api/market/instruments` | All instruments |
| GET | `/api/market/instruments/{exchange}` | Instruments by exchange |

### 6.4 Orders (`/api/orders` — `OrderController`)

| Method | Path | Description |
|---|---|---|
| POST | `/api/orders` | Place order |
| PUT | `/api/orders/{orderId}` | Modify order |
| DELETE | `/api/orders/{orderId}` | Cancel order |
| GET | `/api/orders` | List all orders |
| GET | `/api/orders/{orderId}/history` | Order status history |
| GET | `/api/orders/charges` | Calculate order charges |

### 6.5 Portfolio (`/api/portfolio` — `PortfolioController`)

| Method | Path | Description |
|---|---|---|
| GET | `/api/portfolio/positions` | Get open positions |
| GET | `/api/portfolio/holdings` | Get holdings |
| GET | `/api/portfolio/trades` | Get trades |
| PUT | `/api/portfolio/positions/convert` | Convert position product type |
| GET | `/api/portfolio/pnl/day` | Get day P&L summary |

### 6.6 Account (`/api/account` — `AccountController`)

| Method | Path | Description |
|---|---|---|
| GET | `/api/account/margins/{segment}` | Get margin info by segment |

### 6.7 Paper Trading (`/api/paper-trading` — `PaperTradingController`)

| Method | Path | Description |
|---|---|---|
| GET | `/api/paper-trading/status` | Paper trading enabled status |
| GET | `/api/paper-trading/account` | Virtual account state |
| POST | `/api/paper-trading/account/reset` | Reset virtual account |
| GET | `/api/paper-trading/statistics` | Trading statistics |
| GET | `/api/paper-trading/info` | Paper trading configuration |
| POST | `/api/paper-trading/mode` | Toggle paper/live mode |

### 6.8 Trading History (`/api/history` — `TradingHistoryController`)

| Method | Path | Description |
|---|---|---|
| GET | `/api/history/trades` | Trade history with date range |
| GET | `/api/history/trades/today` | Today's trades |
| GET | `/api/history/strategies` | Strategy execution history |
| GET | `/api/history/daily-summary` | Daily P&L summaries |
| GET | `/api/history/daily-summary/today` | Today's P&L summary |
| POST | `/api/history/position-snapshot` | Capture position snapshot |
| GET | `/api/history/alerts` | Alert history |
| GET | `/api/history/alerts/strategy/{strategyName}` | Alerts by strategy |
| GET | `/api/history/mtm` | MTM snapshot history |

### 6.9 Monitoring (`/api/monitoring` — `MonitoringController`)

| Method | Path | Description |
|---|---|---|
| GET | `/api/monitoring/status` | WebSocket connection status |
| POST | `/api/monitoring/connect` | Connect WebSocket |
| POST | `/api/monitoring/disconnect` | Disconnect WebSocket |
| DELETE | `/api/monitoring/{executionId}` | Stop monitoring execution |
| GET | `/api/monitoring/delta-cache` | Delta cache status |
| POST | `/api/monitoring/delta-cache/refresh` | Force delta cache refresh |
| GET | `/api/monitoring/rate-limiter` | Rate limiter status |
| GET | `/api/monitoring/instrument-cache` | Instrument cache status |
| GET | `/api/monitoring/persistence-buffer` | Persistence buffer status |
| POST | `/api/monitoring/persistence-buffer/flush` | Flush persistence buffer |
| GET | `/api/monitoring/system-health/current` | Current system health |

### 6.10 GTT Orders (`/api/gtt` — `GTTController`)

| Method | Path | Description |
|---|---|---|
| GET | `/api/gtt` | List all GTT orders |
| POST | `/api/gtt` | Create GTT order |
| GET | `/api/gtt/{triggerId}` | Get GTT by ID |
| PUT | `/api/gtt/{triggerId}` | Modify GTT order |
| DELETE | `/api/gtt/{triggerId}` | Delete GTT order |

### 6.11 Health (`/api` — `HealthController`)

| Method | Path | Description |
|---|---|---|
| GET | `/api/health` | System health check |
| GET | `/api/health/sessions` | Active session info |

### 6.12 Backtest (`/api/backtest` — `BacktestController`)

| Method | Path | Description |
|---|---|---|
| POST | `/api/backtest/run` | Run single backtest |
| POST | `/api/backtest/batch` | Run batch of backtests |
| POST | `/api/backtest/run-async` | Run backtest asynchronously |
| GET | `/api/backtest/result/{backtestId}` | Get async backtest result |
| GET | `/api/backtest/results` | List all backtest results |
| GET | `/api/backtest/strategies` | Available backtest strategies |
| DELETE | `/api/backtest/cache` | Clear backtest cache |

---

## 7. Strategy Execution Data Flow

```
REST POST /api/strategies/execute (StrategyRequest)
    │
    ▼
StrategyController → StrategyService.executeStrategy()
    │
    ▼
StrategyFactory.create(StrategyType)
    → Returns: SellATMStraddleStrategy | ShortStrangleStrategy | ATMStraddleStrategy
    │
    ▼
[Pre-flight checks — all must pass]
  1. DailyPnlGateService    — daily profit/loss limits not exceeded
  2. VolatilityFilterService — India VIX above absolute-threshold (12.5)
                               AND VIX 5-min change < spike-threshold (1.0%)
                               AND VIX not too far above prev close
  3. NeutralMarketDetectorServiceV2 — weighted score ≥ weak-neutral-threshold (4)
                                      Regime: STRONG_NEUTRAL (≥6) / WEAK_NEUTRAL (≥4) / TRENDING (<4)
    │
    ▼
[Instrument resolution — from MarketDataEngine cache, NOT inline API calls]
  MarketDataEngine.getSpotPrice("NIFTY")                    → spot price
  MarketDataEngine.getATMStrike("NIFTY")                    → ATM strike
  MarketDataEngine.getPrecomputedStrikeByDelta(instrument, delta, optionType)  → specific strike
  MarketDataEngine.getOptionChain(instrument, expiry)       → full option chain
    │
    ▼
[Order placement — parallel via CompletableFuture]
  UnifiedTradingService.placeOrder() / placeBasketOrder()
    → PaperTradingService (if paper mode) | TradingService → Kite API (if live)
    │
    ▼
[Position monitoring — initiated by MonitoringSetupHelper]
  WebSocketService.subscribe(instrumentTokens)
    → Kite WebSocket tick stream
    → PositionMonitorV2.onTick(Tick)
        │
        ▼
    ExitStrategy[] evaluated in priority order:
        [0]   TimeBasedForcedExitStrategy   — auto square-off at configured time
        [50]  PremiumBasedExitStrategy      — % premium decay/expansion
        [100] PointsBasedExitStrategy       — fixed-point target
        [300] TrailingStopLossStrategy      — dynamic trailing SL
        [400] PointsBasedExitStrategy       — fixed-point stop loss
    │
    ▼
[If ExitResult.exitType != NO_EXIT]
  StraddleExitHandler.executeExit()
    → UnifiedTradingService.placeBasketOrder() (exit orders)
    → StrategyCompletionCallback.onCompleted(reason)
    → TradePersistenceService.persistTrade() [async, non-blocking]
    │
    ▼
[Auto-restart loop (if volatility.auto-restart-enabled = true)]
  MarketStateUpdater evaluates every 30s → publishes MarketStateEvent
  StrategyRestartScheduler listens:
    IF event.neutral == true:
      Wait neutral-market-buffer-ms (60s)
      → DailyPnlGateService.canRestart() check
      → Re-execute strategy
    IF event.neutral == false:
      Reset buffer, wait for next neutral event
```

---

## 8. MarketDataEngine — HFT Cache Architecture

`MarketDataEngine` is the **single most critical service**. All strategy code reads from its caches; nothing calls Kite API inline during execution.

### Cache Stores

All caches are `ConcurrentHashMap<String, CacheEntry<T>>` with time-based expiry.

| Cache | Key Format | Refresh Rate | TTL | Data |
|---|---|---|---|---|
| `spotPriceCache` | `"NIFTY"` | 1s | 2s | Current NIFTY spot price |
| `optionChainCache` | `"NIFTY_WEEKLY"` | 60s | 120s | Full option chain (instruments) |
| `atmStrikeCache` | `"NIFTY"` | 5s (piggyback on delta cycle) | 10s | ATM strike price |
| `deltaMapCache` | `"NIFTY"` | 5s | 10s | Per-strike delta values |
| `strikeByDeltaCache` | `"NIFTY_0.1_CE"` | 5s | 10s | Pre-computed strike for target delta |
| `vwapCache` | `"NIFTY"` | 5s | 10s | Volume-weighted average price |
| `candleCache` | `"NSE:NIFTY 50_minute"` | 60s | 120s | OHLCV candle data |
| `nearestExpiryCache` | `"NIFTY"` | piggyback | 120s | Nearest expiry date |

### Key APIs

```java
// Spot & ATM
Double getSpotPrice(String instrumentType)
Double getATMStrike(String instrumentType)

// Delta
Double getPrecomputedStrikeByDelta(String instrumentType, double targetDelta, String optionType)
Map<String, Double> getDeltaMap(String instrumentType)

// Option chain
List<Instrument> getOptionChain(String instrumentType, String expiry)
String getNearestExpiry(String instrumentType)

// VWAP & Candles
Double getVWAP(String instrumentType)
List<HistoricalData> getCandles(String symbol, String interval)

// Health
Map<String, Object> getEngineStatus()
boolean isHealthy()
```

### Design Rules

1. **Strategy code must ONLY call `MarketDataEngine.get*()` methods** — never `TradingService` directly for price/instrument lookups during execution.
2. If engine is disabled or data is stale, strategies may fall back to inline API calls (degraded mode).
3. Pre-computed delta targets: `0.05, 0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5`
4. Near-ATM scan range (Δ ≥ 0.3): ±10 strikes from ATM.
5. Far-OTM scan range (Δ < 0.3): ±30 strikes from ATM.

---

## 9. Trading Strategies

### 9.1 Strategy Interface

```java
public interface TradingStrategy {
    StrategyExecutionResponse execute(StrategyRequest request, String executionId,
                                     StrategyCompletionCallback completionCallback)
            throws KiteException, IOException;
    String getName();
    StrategyType getType();
}
```

All strategies extend `BaseStrategy` which provides common utilities for instrument resolution, order placement, and delta lookups.

### 9.2 Strategy Types

| StrategyType Enum | Class | Legs | Entry Conditions |
|---|---|---|---|
| `SELL_ATM_STRADDLE` | `SellATMStraddleStrategy` | Sell ATM CE + Sell ATM PE + optional 0.1Δ hedge Buy CE + Buy PE | Neutral market score ≥ min-score AND VIX filter pass |
| `ATM_STRADDLE` | `ATMStraddleStrategy` | Buy ATM CE + Buy ATM PE | VIX filter pass |
| `SHORT_STRANGLE` | `ShortStrangleStrategy` | Sell 0.4Δ CE + Sell 0.4Δ PE + Buy 0.1Δ CE hedge + Buy 0.1Δ PE hedge | VIX filter pass |

### 9.3 Key Config Flags

| Config | Effect |
|---|---|
| `strategy.sell-straddle-hedge-enabled` | Toggles hedge legs on SELL_ATM_STRADDLE |
| `strategy.sell-straddle-hedge-delta` | Delta for hedge legs (default 0.1) |
| `volatility.premium-based-exit-enabled` | Switches from fixed-point to % premium exit mode |
| `volatility.trailing-stop-enabled` / `strategy.trailing-stop-enabled` | Activates TrailingStopLossStrategy |
| `volatility.auto-restart-enabled` | Enables neutral-market re-entry loop |

### 9.4 Strategy Lifecycle

```
PENDING → EXECUTING → ACTIVE → COMPLETED / FAILED / SKIPPED
```

- `PENDING`: Strategy queued
- `EXECUTING`: Orders being placed
- `ACTIVE`: Positions open, monitoring active
- `COMPLETED`: All positions closed (target/SL/time/manual)
- `FAILED`: Error during execution
- `SKIPPED`: Pre-flight filter blocked entry (VIX, neutral market)

### 9.5 Completion Reasons

`TARGET_HIT`, `STOPLOSS_HIT`, `MANUAL_STOP`, `TIME_BASED_EXIT`, `ERROR`, `DAY_PROFIT_LIMIT_HIT`, `DAY_LOSS_LIMIT_HIT`, `OTHER`

---

## 10. Exit Strategy System (PositionMonitorV2)

`PositionMonitorV2` evaluates a priority-ordered chain of `ExitStrategy` implementations on every WebSocket tick.

### 10.1 ExitStrategy Interface

```java
public interface ExitStrategy {
    int getPriority();                          // Lower = evaluated first
    ExitResult evaluate(ExitContext context);    // Stateless evaluation
    String getName();
    boolean isEnabled();
}
```

### 10.2 Priority Chain

| Priority | Strategy | Trigger | ExitResult Type |
|---|---|---|---|
| 0 | `TimeBasedForcedExitStrategy` | Clock ≥ auto-square-off-time (default 15:10 IST) | EXIT_ALL |
| 50 | `PremiumBasedExitStrategy` | Combined LTP decays by target-decay-pct OR expands by stop-loss-expansion-pct | EXIT_ALL |
| 100 | `PointsBasedExitStrategy` | Cumulative P&L ≥ target points (TARGET) | EXIT_ALL |
| 300 | `TrailingStopLossStrategy` | P&L drops from high-water mark by trailing-distance-points | EXIT_ALL |
| 400 | `PointsBasedExitStrategy` | Cumulative P&L ≤ -stop-loss-points (STOP_LOSS) | EXIT_ALL |

### 10.3 ExitResult Types

```java
public enum ExitType {
    NO_EXIT,       // Continue monitoring
    EXIT_ALL,      // Exit all legs immediately
    EXIT_LEG,      // Exit specific leg only
    ADJUST_LEG     // Exit leg and request replacement
}
```

### 10.4 Design Rules

- All `ExitStrategy` implementations must be **stateless** — all state flows through `ExitContext`.
- Return `ExitResult.NO_EXIT_RESULT` (pre-allocated singleton) when no action needed — **zero allocation on hot path**.
- Avoid `new`, `BigDecimal`, or `Iterator` allocations in per-tick code.
- Use `double` primitives for all price arithmetic.

---

## 11. Neutral Market Detection V1 (5-Signal Engine)

> **Status:** Legacy — superseded by V2/V3 for active strategy execution.
> V1 (`NeutralMarketDetectorService`) is retained for backward compatibility and provides `computeADXSeries()` used by V2 and V3.

`NeutralMarketDetectorService` scores 5 independent signals. Each passing signal contributes **+2 points**. Maximum score = 10. Minimum score to allow trade entry is configurable (default 6 dev / 7 prod).

### 11.1 Signal Details

| # | Signal | Metric | Threshold (Default) | Data Source |
|---|---|---|---|---|
| 1 | VWAP Deviation | \|NIFTY price − VWAP\| / VWAP | ≤ 0.2% | `MarketDataEngine.getVWAP()` |
| 2 | ADX Trend Strength | ADX(7) on 1-min candles | < 20.0 | `MarketDataEngine.getCandles()` |
| 3 | Gamma Pin (Max OI Strike) | Distance from max open-interest strike | ≤ 0.2% | Option chain OI data |
| 4 | Range Compression | Range of last 10 × 1-min candles | ≤ 0.35% | `MarketDataEngine.getCandles()` |
| 5 | Dual Premium Decay | Both CE + PE LTP declining vs previous snapshot | Snapshot ≥ 20s apart, both decay ≥ 0.5% | Option chain LTP |

### 11.2 Data Flow

```
MarketDataEngine (cache) → NeutralMarketDetectorService (scores)
    → MarketStateUpdater (publishes MarketStateEvent every 30s)
    → StrategyRestartScheduler (listens for NEUTRAL → re-entry)
```

### 11.3 Special Cases

- **Expiry day**: Tighter range-compression threshold (0.2%), higher minimum score (8).
- **Data unavailable**: Configurable fail-safe (`allow-on-data-unavailable: true` = allow trade).
- **Cache**: Result cached for 30s to avoid redundant computation.

---

## 12. Neutral Market Detection V2 (Weighted Confidence Engine)

> **Since:** Version 5.0
> **Class:** `NeutralMarketDetectorServiceV2`
> **Active consumers:** `MarketStateUpdater`, `SellATMStraddleStrategy`, `StrategyRestartScheduler`

Redesign of V1 to increase tradable opportunities, eliminate over-filtering, and provide dynamic confidence-based regime classification optimised for SELL ATM STRADDLE.

### 12.1 Key Improvements over V1

| Aspect | V1 | V2 |
|---|---|---|
| Scoring | Binary +2 per signal (0–10) | Weighted 0–10 with configurable weights |
| Regime | Pass/fail only | STRONG_NEUTRAL / WEAK_NEUTRAL / TRENDING |
| Signals | 5 fixed | 5 core + 1 expiry-only (Gamma Pin) |
| New signals | — | Price Oscillation, VWAP Pullback Entry |
| Removed | — | Premium Decay (lagging) |
| Gamma Pin | Always evaluated | Expiry day only |
| Time adaptation | None | Opening -1, mid-session 0, pre-close +1 |
| Position sizing | Fixed | Confidence-based lot multiplier (1×/2×/3×) |
| Caching | Single-slot AtomicReference | Per-instrument ConcurrentHashMap |
| Error cache | Full 30s TTL | Short 5s TTL for fast recovery |

### 12.2 Signal Weights & Scoring

| # | Signal | Weight | Condition | Data Source |
|---|---|---|---|---|
| 1 | VWAP Proximity | 3 | \|price − VWAP\| / VWAP < threshold (0.2%) | `MarketDataEngine.getVWAP()` |
| 2 | Range Compression | 2 | (highestHigh − lowestLow) / spotPrice < threshold (0.35%) | `MarketDataEngine.getCandles()` |
| 3 | Price Oscillation | 2 | Close-to-close direction reversals ≥ min (4 in 10 candles) | `MarketDataEngine.getCandles()` |
| 4 | VWAP Pullback | 2 | Price deviated ≥ 0.3% from VWAP then reverted within 0.1% | `MarketDataEngine.getCandles()` |
| 5 | ADX Trend | 1 | ADX(7) < threshold (20.0) — confirmatory only | `NeutralMarketDetectorService.computeADXSeries()` |
| 6 | Gamma Pin | 1 | **Expiry day only** — spot within 0.2% of max-OI strike | NFO instrument OI data |

**Total (non-expiry):** 3+2+2+2+1 = **10** (matches `MAX_SCORE`)
**Total (expiry day):** 3+2+2+2+1+1 = **11** → capped to 10 (Gamma Pin acts as bonus/compensator)

### 12.3 Regime Classification

| Regime | Score Threshold | `tradable` | Lot Multiplier |
|---|---|---|---|
| `STRONG_NEUTRAL` | ≥ 6 | `true` | 2× (score 6–7), 3× (score ≥ 8) |
| `WEAK_NEUTRAL` | ≥ 4 | `true` | 1× |
| `TRENDING` | < 4 | `false` | 0× (no trade) |

### 12.4 Time-Based Score Adaptation

| Session | IST Window | Score Adjustment | Rationale |
|---|---|---|---|
| Opening | 09:15–10:00 | **-1** (stricter) | High opening volatility |
| Mid-session | 10:00–13:30 | **0** (normal) | Stable session |
| Pre-close | 13:30–15:30 | **+1** (lenient) | Theta decay accelerates |

### 12.5 Result Object — `NeutralMarketResult`

Immutable after construction. Provides both V2 accessors and backward-compatible V1 accessors.

```java
public final class NeutralMarketResult {
    // V2 accessors
    boolean isTradable();           // score >= weakNeutralThreshold
    int getScore();                 // Weighted score (0–10)
    double getConfidence();         // score / MAX_SCORE (0.0–1.0)
    String getRegime();             // STRONG_NEUTRAL | WEAK_NEUTRAL | TRENDING
    Map<String, Boolean> getSignalBreakdown();  // Per-signal pass/fail

    // V1 backward-compat accessors
    boolean neutral();              // alias for isTradable()
    int totalScore();               // alias for getScore()
    int maxScore();                 // always 10
    List<SignalResult> signals();   // Per-signal results
    String summary();               // Human-readable summary
    Instant evaluatedAt();          // Evaluation timestamp
    int minimumRequired();          // Configured minimum score

    // Factory methods
    static NeutralMarketResult disabled();         // Filter off → max score
    static NeutralMarketResult dataUnavailable(...); // Fallback on error
}
```

### 12.6 Signal Result — `SignalResult`

```java
public record SignalResult(String name, int score, int maxScore, boolean passed, String detail) {
    static SignalResult passed(String name, int weight, String detail);
    static SignalResult failed(String name, int weight, String detail);
    static SignalResult unavailable(String name, int weight, String reason);
    static SignalResult partial(String name, int partialScore, int weight, String detail);
}
```

### 12.7 Caching Architecture

| Feature | Implementation |
|---|---|
| Cache type | `ConcurrentHashMap<String, CachedResult>` — per-instrument |
| Cache TTL | 30s (configurable via `neutral-market.cache-ttl-ms`) |
| Error TTL | ~5s (short TTL allows fast recovery from transient API failures) |
| Thread safety | Lock-free reads via ConcurrentHashMap; immutable result objects |
| Instrument token cache | `ConcurrentHashMap<String, String>` — resolved once, cached forever |

### 12.8 Data Flow

```
MarketDataEngine (cache) → NeutralMarketDetectorServiceV2.evaluate("NIFTY")
    │
    ├─ getIndexPrice() → spot price
    ├─ getCandles() → 1-min candles (shared across VWAP, Range, Oscillation, Pullback)
    ├─ getVWAP() → pre-computed VWAP (fallback: compute from candles)
    ├─ getNearestWeeklyExpiry() → expiry date (fallback: NFO instrument scan)
    │
    ▼
    Evaluate 5–6 signals → aggregate weighted score → time adjustment
    → classify regime → build NeutralMarketResult (immutable)
    → cache per-instrument with TTL
    │
    ▼
MarketStateUpdater.evaluateAndPublish() [every 30s]
    → publishes MarketStateEvent with V2 result
    → StrategyRestartScheduler listens for tradable events → re-entry

### 12.9 HFT Safety

- All price arithmetic uses `double` primitives — no BigDecimal on hot path
- Indexed `for` loops — no Iterator/Stream allocations
- Per-signal logs at DEBUG level (only 1 summary INFO line per 30s cycle)
- No `String.format()` in log arguments — raw doubles passed to SLF4J
- `Instant.ofEpochMilli(startTime)` reused — no redundant clock calls
- Epoch-day arithmetic for same-day checks — no `new Date()` or Calendar allocations
- `KiteException extends Throwable` (not Exception) — requires explicit catch clause
```

---

## 13. Neutral Market Detection V3 (3-Layer Tradable Opportunity Detector)

> **Since:** Version 6.0
> **Class:** `NeutralMarketDetectorServiceV3`
> **Config:** `NeutralMarketV3Config` (`neutral-market-v3.*`)
> **Status:** New — not yet wired into active strategy execution (V2 remains active consumer). Available for integration.

A production-grade, HFT-safe 3-layer detection engine that determines not just *whether* the market is neutral, but whether a **real-time tradable opportunity** exists right now for SELL ATM STRADDLE. Combines macro regime analysis, microstructure opportunity detection, and breakout risk assessment.

### 13.1 Key Improvements over V2

| Aspect | V2 | V3 |
|---|---|---|
| Layers | 1 (flat scoring) | 3 (Regime + Microstructure + Breakout Risk) |
| Entry timing | Regime-only | Micro signals detect precise entry moment |
| Breakout protection | None | Breakout Risk layer blocks imminent breakouts |
| Scoring | 0–10 weighted | Regime 0–9 + Micro 0–5 = Final 0–15 (time-adjusted) |
| Regime source | Same signals for scoring + tradability | Regime ≠ tradability (micro can override borderline regime) |
| Caching | 30s TTL | 15s TTL (faster refresh for micro signals) |
| Error cache | ~5s TTL | ~5s TTL (same) |
| New signals | — | VWAP Pullback Momentum (M1), HF Oscillation (M2), Micro Range Stability (M3) |
| New safety | — | Breakout Risk: tight range + edge proximity + momentum → block |
| Position sizing | Score-based | Score-based with breakout-aware multiplier (0 when not tradable) |

### 13.2 Architecture: 3-Layer Detection

```
Layer 1: REGIME (0–9 pts) — Macro neutrality
    ├─ R1: VWAP Proximity (+3)     — |price − VWAP| / VWAP < 0.2%
    ├─ R2: Range Compression (+2)  — (high−low) / price < 0.35% over 10 candles
    ├─ R3: Price Oscillation (+2)  — ≥4 direction reversals in 10 candles
    ├─ R4: ADX Trend (+1)         — ADX(7) < 20.0 (confirmatory)
    └─ R5: Gamma Pin (+1)         — expiry day only: spot within 0.2% of max-OI strike

Layer 2: MICROSTRUCTURE (0–5 pts) — Immediate tradable signal
    ├─ M1: VWAP Pullback Momentum (+2) — price deviated then slope reverting toward VWAP
    ├─ M2: HF Oscillation (+2)         — ≥4 flips AND avg move < 0.1% (small-amplitude chop)
    └─ M3: Micro Range Stability (+1)  — (high−low) / price < 0.1% over 5 candles

Layer 3: BREAKOUT RISK — Safety gate
    ├─ Tight range (consolidation < 0.15%)
    ├─ Edge proximity (price within 20% of range from high/low)
    └─ Momentum buildup (3 consecutive same-direction candles)
    → 3/3 = HIGH (block), 2/3 = MEDIUM, 0–1/3 = LOW
```

### 13.3 Regime Classification (from Regime Layer score)

| Regime | Regime Score | Classification |
|---|---|---|
| `STRONG_NEUTRAL` | ≥ 6 | High confidence, full position size |
| `WEAK_NEUTRAL` | ≥ 4 | Moderate confidence, reduced size |
| `TRENDING` | < 4 | Not suitable for straddle |

### 13.4 Final Decision Logic

```
if (breakoutRisk == HIGH)                                           → NOT tradable
if (regimeScore >= weakNeutralThreshold AND microScore >= 2)        → tradable
if (regimeScore >= microNeutralOverrideRegime AND microScore >= microNeutralOverrideMicro)
                                                                    → tradable (micro-neutral opportunity)
else                                                                → NOT tradable
```

Key: The micro-neutral override (default regime ≥ 3 AND micro ≥ 3) allows entry when the regime is borderline but microstructure signals are very strong — catching opportunities that V2 would miss.

### 13.5 Scoring & Confidence

| Metric | Range | Computation |
|---|---|---|
| Regime score | 0–9 | Sum of passed regime signal weights |
| Micro score | 0–5 | Sum of passed micro signal weights |
| Time adjustment | −1, 0, +1 | Opening -1 / Mid 0 / Pre-close +1 |
| Final score | 0–15 | `min(max(regime + micro + timeAdj, 0), 15)` |
| Confidence | 0.0–1.0 | `finalScore / 15.0` |
| Lot multiplier | 0–3 | 0 (not tradable or score < 5), 1 (≥5), 2 (≥7), 3 (≥10) |

**Note:** `lotMultiplier` is forced to 0 when `tradable == false` — consumers can safely check either field.

### 13.6 Microstructure Signals (Detail)

#### M1: VWAP Pullback Momentum (+2)

Detects mean-reversion entry: price moved *away* from VWAP, then started reverting *toward* it.

1. In last N candles, find the point of maximum deviation from VWAP
2. Deviation must exceed `microVwapPullbackDeviationThreshold` (0.15%)
3. Last `slopeCandles` (3) must show decreasing deviation (with 0.02% tolerance for noise)
4. Overall slope must be downward (last dev < first dev)
5. Current spot price must confirm reversion (not diverging again)

#### M2: HF Oscillation (+2)

Dual condition: high flip count AND small average move per candle.

- Direction flips ≥ `microOscillationMinFlips` (4) in last 8 candles
- Average absolute move / price < `microOscillationMaxAvgMove` (0.1%)
- Rejects large swings (fake chop) via the amplitude check

#### M3: Micro Range Stability (+1)

Last `microRangeCandles` (5) × 1-min candles confined to a very tight range:
- `(highestHigh - lowestLow) / spotPrice < microRangeThreshold` (0.1%)

### 13.7 Breakout Risk Detection (Detail)

Three conditions that together form the classic breakout setup:

1. **Tight Range:** `range / spotPrice < breakoutTightRangeThreshold` (0.15%)
2. **Edge Proximity:** `spotPrice` within `breakoutEdgeProximityPct` (20%) of range from high or low
3. **Momentum Buildup:** Last `breakoutMomentumCandles` (3) candles all closing in the same direction (flat candles don't kill momentum — uses strict inequality)

| Signals Met | Risk Level | Action |
|---|---|---|
| 3/3 | `HIGH` | Block entry completely |
| 2/3 | `MEDIUM` | Entry allowed (logged for monitoring) |
| 0–1/3 | `LOW` | Safe to enter |

### 13.8 Result Object — `NeutralMarketResultV3`

Immutable after construction. Thread-safe for concurrent reads.

```java
public final class NeutralMarketResultV3 {
    boolean isTradable();                    // Final decision incorporating all 3 layers
    int getRegimeScore();                    // Regime layer (0–9)
    int getMicroScore();                     // Microstructure layer (0–5)
    int getFinalScore();                     // regime + micro, time-adjusted (0–15)
    double getConfidence();                  // finalScore / 15.0 (0.0–1.0)
    Regime getRegime();                      // STRONG_NEUTRAL | WEAK_NEUTRAL | TRENDING
    BreakoutRisk getBreakoutRisk();          // LOW | MEDIUM | HIGH
    boolean isMicroTradable();              // micro layer independently tradable
    int getRecommendedLotMultiplier();       // 0–3 (0 when not tradable)
    Map<String, Boolean> getSignals();       // Per-signal pass/fail
    String getSummary();                     // Human-readable: R[V✓ RC✗ O✓ A✓]=6 M[VP✓ HF✗ RS✓]=3 BR=LOW → TRADE
    Instant getEvaluatedAt();                // Evaluation timestamp

    // Backward-compat accessors
    boolean neutral();                       // alias for isTradable()
    int totalScore();                        // alias for getFinalScore()
    int maxScore();                          // always 15

    // Factory methods
    static NeutralMarketResultV3 disabled();           // Pre-allocated singleton (zero alloc)
    static NeutralMarketResultV3 dataUnavailable(...); // Fallback on error
}
```

### 13.9 Caching Architecture

| Feature | Implementation |
|---|---|
| Cache type | `ConcurrentHashMap<String, CachedResult>` — per-instrument |
| Cache TTL | 15s (configurable via `neutral-market-v3.cache-ttl-ms`) |
| Error TTL | ~5s (backdated timestamp trick for fast recovery) |
| Thread safety | Lock-free reads via ConcurrentHashMap; immutable result objects |
| Instrument token cache | `ConcurrentHashMap<String, String>` — resolved once, cached forever |
| Hot-path behavior | Cache hit → return as-is (zero allocation). Evaluation runs only on cache miss. |

### 13.10 Data Flow

```
MarketDataEngine (cache) → NeutralMarketDetectorServiceV3.evaluate("NIFTY")
    │
    ├─ getIndexPrice() → spot price (fallback: tradingService.getLTP)
    ├─ getCandles() → 1-min candles (shared across ALL signals)
    ├─ getVWAP() → pre-computed VWAP (fallback: compute from candles)
    ├─ getNearestWeeklyExpiry() → expiry date (for gamma pin)
    │
    ▼
Layer 1: Evaluate 5 regime signals → regimeScore (0–9) → classify Regime
Layer 2: Evaluate 3 micro signals → microScore (0–5) → microTradable
Layer 3: Evaluate breakout risk → BreakoutRisk (LOW/MEDIUM/HIGH)
    │
    ▼
Time adjustment → Final decision → Confidence → Lot multiplier
    → Build NeutralMarketResultV3 (immutable) → Cache with TTL
```

### 13.11 HFT Safety

- All price arithmetic uses `double` primitives — zero BigDecimal on hot path
- Indexed `for` loops only — no Iterator, Stream, or lambda
- Pre-allocated `NeutralMarketResultV3.DISABLED` singleton for disabled state
- `ConcurrentHashMap` for per-instrument cache — no synchronized blocks
- Evaluation runs on 15s cache TTL cycle, not per-tick
- Market data read exclusively from `MarketDataEngine` cache (zero inline API calls except gamma pin OI on expiry days)
- Epoch-day arithmetic for same-day checks — no Calendar or LocalDate allocations
- `KiteException extends Throwable` (not Exception) in SDK 3.5.1 — requires explicit multi-catch

### 13.12 Integration Points

| Consumer | Usage | Status |
|---|---|---|
| `SellATMStraddleStrategy` | Pre-flight neutral market check | Not yet wired (uses V2) |
| `MarketStateUpdater` | Publishes MarketStateEvent every 30s | Not yet wired (uses V2) |
| `StrategyRestartScheduler` | Restart gating on neutral events | Not yet wired (uses V2) |
| Direct API | `evaluate("NIFTY")`, `isMarketTradable()`, `getRecommendedLotMultiplier()` | Available |

---

## 14. Paper Trading Simulation

`PaperTradingService` provides a complete in-memory trading simulation with realistic charge computation.

### Key Components

| Class | Purpose |
|---|---|
| `PaperTradingService` | In-memory order book, position tracker, P&L calculation |
| `PaperAccount` | Virtual account state (balance, margin, positions) |
| `PaperOrder` | Simulated order with slippage and optional delays |
| `PaperPosition` | Simulated position with real-time MTM |
| `ZerodhaChargeCalculator` | Accurate brokerage + STT + GST + SEBI + stamp duty calculation |
| `OrderCharges` (entity) | Charge breakdown value object |

### Features

- Configurable execution delay (simulates network latency)
- Configurable slippage percentage
- Configurable order rejection probability
- Full charge simulation matching Zerodha's fee structure
- Account reset capability
- Statistics and performance tracking

---

## 15. Backtesting Engine

The backtest module replays historical market data through the strategy engine.

### Components

| Class | Purpose |
|---|---|
| `BacktestEngine` | Core replay engine — iterates historical candles and triggers strategy logic |
| `HistoricalDataFetcher` | Fetches historical OHLCV candles from Kite API |
| `InstrumentResolver` | Resolves instrument tokens for historical dates |
| `HistoricalCandleAdapter` | Converts historical candles to simulated tick feed |
| `TickFeedMerger` | Merges CE + PE + underlying tick feeds chronologically |
| `BacktestService` | Orchestrates full backtest: fetch data → resolve instruments → run engine → return results |
| `BacktestController` | REST API for running backtests (sync, async, batch) |

### DTOs

- `BacktestRequest` — date range, strategy type, parameters
- `BacktestResult` — trades, P&L, metrics
- `BacktestTrade` — individual trade within a backtest

### Configuration

- `backtest.rate-limit-delay-ms: 350` — throttle Kite API calls during data fetch
- `historical.replay.sleep-millis-per-second: 2` (dev) / `0` (prod) — replay speed
- VIX filter can be disabled for backtests: `volatility.backtest-enabled: false`
- Auto square-off in backtests: `volatility.auto-square-off-backtest-enabled: true`

---

## 16. Database Schema & Entities

### 16.1 Tables (from Flyway Migrations)

#### `trades` — Individual Trade Executions
| Column Group | Key Columns |
|---|---|
| Identity | `id`, `user_id`, `order_id`, `execution_id`, `exchange_order_id` |
| Instrument | `trading_symbol`, `exchange`, `instrument_token`, `option_type`, `strike_price`, `expiry` |
| Trade | `transaction_type`, `order_type`, `product`, `quantity` |
| Entry | `entry_price`, `entry_timestamp`, `entry_latency_ms` |
| Exit | `exit_price`, `exit_timestamp`, `exit_order_id`, `exit_latency_ms` |
| P&L | `realized_pnl`, `unrealized_pnl` |
| Charges | `brokerage`, `stt`, `exchange_charges`, `gst`, `sebi_charges`, `stamp_duty`, `total_charges` |
| Status | `status`, `status_message`, `trading_mode`, `trading_date` |

#### `strategy_executions` — Strategy Execution Lifecycle
| Column Group | Key Columns |
|---|---|
| Identity | `id`, `execution_id` (unique), `root_execution_id`, `parent_execution_id`, `user_id` |
| Strategy | `strategy_type`, `instrument_type`, `expiry` |
| State | `status`, `completion_reason`, `message` |
| Config | `stop_loss_points`, `target_points`, `lots` |
| Financial | `entry_price`, `exit_price`, `realized_pnl`, `total_charges` |
| Context | `spot_price_at_entry`, `atm_strike_used`, `trading_mode`, `auto_restart_count` |
| Timing | `started_at`, `completed_at`, `duration_ms` |

#### `order_legs` — Individual Order Legs Within Strategy
| Column Group | Key Columns |
|---|---|
| Identity | `id`, `strategy_execution_id` (FK), `order_id`, `trading_symbol` |
| Entry | `entry_price`, `entry_transaction_type`, `entry_timestamp`, `entry_latency_ms` |
| Exit | `exit_order_id`, `exit_price`, `exit_timestamp`, `exit_latency_ms`, `exit_status` |
| P&L | `realized_pnl`, `lifecycle_state` |
| Greeks | `delta_at_entry`, `gamma_at_entry`, `theta_at_entry`, `vega_at_entry`, `iv_at_entry` |

#### `delta_snapshots` — Greeks Snapshots for Analysis
| Key Columns | `execution_id`, `spot_price`, `option_price`, `delta`, `gamma`, `theta`, `vega`, `implied_volatility`, `time_to_expiry`, `snapshot_type`, `snapshot_timestamp` |

#### `daily_pnl_summary` — Aggregated Daily P&L
| Key Columns | `user_id`, `trading_date`, `realized_pnl`, `net_pnl`, `total_trades`, `winning_trades`, `losing_trades`, `win_rate`, `nifty_open`, `nifty_close`, `trading_mode` |

#### `position_snapshots` — EOD Position Snapshots
| Key Columns | `user_id`, `snapshot_date`, `trading_symbol`, `quantity`, `buy_price`, `sell_price`, `pnl`, `realised`, `unrealised`, `m2m` |

#### `order_timing_metrics` — HFT Latency Analysis
| Key Columns | `order_id`, `trading_symbol`, `initiation_to_send_ms`, `send_to_ack_ms`, `ack_to_execution_ms`, `total_latency_ms`, `expected_price`, `actual_price`, `slippage_points`, `slippage_percent` |

#### `user_sessions` — Kite Session Persistence (Cloud Run Recovery)
| Key Columns | `user_id` (unique), `kite_user_id`, `access_token`, `public_token`, `created_at`, `last_accessed_at`, `expires_at`, `active`, `version` (optimistic lock) |

### 16.2 Entity-to-Table Mapping

| Entity Class | Table | Repository |
|---|---|---|
| `TradeEntity` | `trades` | `TradeRepository` |
| `StrategyExecutionEntity` | `strategy_executions` | `StrategyExecutionRepository` |
| `StrategyConfigHistoryEntity` | *(auto-generated)* | `StrategyConfigHistoryRepository` |
| `OrderLegEntity` | `order_legs` | `OrderLegRepository` |
| `OrderTimingEntity` | `order_timing_metrics` | `OrderTimingRepository` |
| `DeltaSnapshotEntity` | `delta_snapshots` | `DeltaSnapshotRepository` |
| `MTMSnapshotEntity` | *(auto-generated)* | `MTMSnapshotRepository` |
| `PositionSnapshotEntity` | `position_snapshots` | `PositionSnapshotRepository` |
| `DailyPnLSummaryEntity` | `daily_pnl_summary` | `DailyPnLSummaryRepository` |
| `AlertHistoryEntity` | *(auto-generated)* | `AlertHistoryRepository` |
| `WebSocketEventEntity` | *(auto-generated)* | `WebSocketEventRepository` |
| `SystemHealthSnapshotEntity` | *(auto-generated)* | `SystemHealthSnapshotRepository` |
| `UserSessionEntity` | `user_sessions` | `UserSessionRepository` |

All repositories are standard `JpaRepository<Entity, Long>`.

---

## 17. Persistence Layer

### Write Path (Async, Non-Blocking)

```
Strategy thread
    → TradePersistenceService.persistTrade() [async]
        → PersistenceBufferService.buffer(entity) [in-memory buffer]
            → Batch flush to DB via HikariCP (every N records or on timer)
```

### Services

| Service | Purpose |
|---|---|
| `TradePersistenceService` | Async trade/order write-through — accepts entities and dispatches to buffer |
| `PersistenceBufferService` | Buffers writes and flushes in batches to minimize DB round-trips |
| `EndOfDayPersistenceService` | Aggregates and writes daily P&L summary at market close |
| `SystemHealthMonitorService` | Periodic system health snapshots (CPU, memory, cache hit rates) |
| `DataCleanupService` | Scheduled old-data pruning (cron: `0 0 2 * * ?` = 2 AM daily) |

### Async Thread Pool (`AsyncPersistenceConfig`)

- Custom thread pool with `UserContextPropagatingTaskDecorator` (preserves user context across async boundaries)
- `HFTSafeRejectionHandler` (logs and drops instead of throwing exceptions on pool saturation)

### Retention Policies (Configurable)

| Data Type | Dev Retention | Prod Retention |
|---|---|---|
| Trades | 365 days | 730 days |
| Delta snapshots | 90 days | 180 days |
| Position snapshots | 180 days | 365 days |
| Order timing | 90 days | 180 days |
| Alerts | 90 days | — |
| MTM snapshots | 30 days | — |
| System health | 7 days | — |

---

## 18. Multi-User Session Management

### `UserSessionManager`

- Manages per-user Kite Connect sessions
- Stores sessions in both memory (`ConcurrentHashMap`) and database (`UserSessionEntity`)
- Supports Cloud Run container restart recovery: on startup, loads active sessions from DB
- Session identified by `user_id` from `X-User-Id` HTTP header

### `CurrentUserContext`

- `ThreadLocal<String>` holding current user ID
- Set by `UserContextFilter` (servlet filter) on every request from `X-User-Id` header
- Propagated to async threads via `UserContextPropagatingTaskDecorator`
- Usage: `CurrentUserContext.get()` returns the current user's ID anywhere in the call chain

### `UserContextFilter`

- Servlet filter that runs on every request
- Extracts user ID from `X-User-Id` header
- Sets MDC (Mapped Diagnostic Context) for logging
- Sets `CurrentUserContext` for business logic

---

## 19. Key Interfaces & Contracts

### 19.1 `TradingStrategy` (Strategy Interface)

```java
public interface TradingStrategy {
    StrategyExecutionResponse execute(StrategyRequest request, String executionId,
                                     StrategyCompletionCallback completionCallback);
    String getName();
    StrategyType getType();
}
```

### 19.2 `ExitStrategy` (Exit Strategy Interface)

```java
public interface ExitStrategy {
    int getPriority();                       // Lower = evaluated first
    ExitResult evaluate(ExitContext context); // Must be stateless
    String getName();
    boolean isEnabled();
}
```

### 19.3 `ApiResponse<T>` (Standard API Wrapper)

```java
public class ApiResponse<T> {
    boolean success;
    String message;
    T data;

    static <T> ApiResponse<T> success(T data);
    static <T> ApiResponse<T> success(String message, T data);
    static <T> ApiResponse<T> error(String message);
}
```

### 19.4 `StrategyCompletionCallback`

Callback interface invoked by strategy execution when a strategy completes. Used by `StrategyService` to update state and trigger auto-restart.

### 19.5 `MarketStateEvent` (Record)

```java
public record MarketStateEvent(
    String instrumentType,
    boolean neutral,
    int score,
    int maxScore,
    NeutralMarketResult result,
    Instant evaluatedAt
) {}
```

### 19.6 `NeutralMarketResult` (V2 Result Object)

Immutable result of neutral market evaluation. Provides both V2 and backward-compatible V1 accessors. See §12.5 for full API.

### 19.7 `SignalResult` (V2 Signal Record)

```java
public record SignalResult(String name, int score, int maxScore, boolean passed, String detail) {
    static SignalResult passed(String name, int weight, String detail);
    static SignalResult failed(String name, int weight, String detail);
    static SignalResult unavailable(String name, int weight, String reason);
    static SignalResult partial(String name, int partialScore, int weight, String detail);
}
```

### 19.8 `NeutralMarketResultV3` (V3 Result Object)

Immutable 3-layer composite result. See §13.8 for full API.

### 19.9 `Regime` (V3 Enum)

```java
public enum Regime { STRONG_NEUTRAL, WEAK_NEUTRAL, TRENDING }
```

### 19.10 `BreakoutRisk` (V3 Enum)

```java
public enum BreakoutRisk { LOW, MEDIUM, HIGH }
```

---

## 20. Enums & Domain Model

| Enum | Values | Purpose |
|---|---|---|
| `StrategyType` | `ATM_STRADDLE`, `SELL_ATM_STRADDLE`, `SHORT_STRANGLE` | Trading strategy selection |
| `StrategyStatus` | `PENDING`, `EXECUTING`, `ACTIVE`, `COMPLETED`, `FAILED`, `SKIPPED` | Execution lifecycle |
| `StrategyCompletionReason` | `TARGET_HIT`, `STOPLOSS_HIT`, `MANUAL_STOP`, `TIME_BASED_EXIT`, `ERROR`, `DAY_PROFIT_LIMIT_HIT`, `DAY_LOSS_LIMIT_HIT`, `OTHER` | Why strategy ended |
| `SlTargetMode` | `POINTS`, `PREMIUM`, `MTM` | SL/target calculation mode |
| `BotStatus` | *(bot lifecycle states)* | Overall bot state |
| `ExitResult.ExitType` | `NO_EXIT`, `EXIT_ALL`, `EXIT_LEG`, `ADJUST_LEG` | Exit action to take |
| `Regime` | `STRONG_NEUTRAL`, `WEAK_NEUTRAL`, `TRENDING` | V3 market regime classification (see §13.3) |
| `BreakoutRisk` | `LOW`, `MEDIUM`, `HIGH` | V3 breakout risk assessment (see §13.7) |

---

## 21. Deployment & Infrastructure

### Docker

```dockerfile
FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app
COPY target/*.jar zerodhabot_genai-exec.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/zerodhabot_genai-exec.jar"]
```

### Kubernetes

- **Deployment** (`deployment.yaml`): 2 replicas, image `zerodhabot_genai_3:latest`, port 8080
- **Service** (`service.yaml`): NodePort service exposing port 8080 → nodePort 30080

### Build

```bash
# Build
./mvnw clean package -DskipTests

# Run (dev)
java -jar target/zerodhabot_genai-exec.jar

# Run (prod)
java -jar target/zerodhabot_genai-exec.jar --spring.profiles.active=prod

# Docker build
docker build -t zerodhabot_genai_3:latest .
```

### Environment Variables (Prod)

| Variable | Purpose |
|---|---|
| `KITE_API_KEY` | Kite Connect API key |
| `KITE_API_SECRET` | Kite Connect API secret |
| `KITE_ACCESS_TOKEN` | Session access token |
| `DB_HOST` | PostgreSQL host |
| `DB_PORT` | PostgreSQL port (default: 5432) |
| `DB_NAME` | Database name (default: `tradingbot`) |
| `DB_USERNAME` | DB username |
| `DB_PASSWORD` | DB password |

### Actuator Endpoints

- `/actuator/health` — Health check
- `/actuator/info` — App info
- `/actuator/metrics` — Metrics
- `/actuator/prometheus` — Prometheus metrics (prod only)

### Swagger UI

- Dev/Prod: `/swagger-ui.html`
- API docs: `/api-docs`

---

## 22. Testing

### Test Location

`src/test/java/com/tradingbot/`

### Test Files

| Test Class | Package | Tests |
|---|---|---|
| `PaperTradingServiceTest` | `paper/` | Paper order simulation, charge calculation |
| `PositionMonitorV2Test` | `service/strategy/monitoring/` | Exit strategy evaluation, priority ordering |
| `NeutralMarketDetectorServiceTest` | `service/strategy/` | V1: 5-signal scoring, edge cases |
| `NeutralMarketDetectorServiceV2Test` | `service/strategy/` | V2: Weighted scoring, regime classification, oscillation, pullback, time adaptation, lot multiplier, caching (24 tests) |
| `DailyPnlGateServiceTest` | `service/strategy/` | Daily P&L limit enforcement |
| `StrategyRestartSchedulerTest` | `service/strategy/` | Neutral market restart gating (15 tests, uses V2) |
| `MarketStateUpdaterTest` | `service/strategy/` | Market state event publishing (4 tests, uses V2) |
| `VolatilityFilterServiceTest` | `service/strategy/` | VIX filter logic |
| `BotStatusServiceTest` | `service/` | Bot lifecycle state management |
| `UserSessionManagerTest` | `service/session/` | Multi-user session handling, DB recovery |
| `CandleUtilsTest` | `util/` | OHLCV candle computation helpers |

### Test Patterns

- **Framework**: JUnit 5 + Mockito
- **Mocking**: `@Mock` for `MarketDataEngine`, `UnifiedTradingService`, `TradingService`
- **Injection**: `@InjectMocks` for the class under test
- **No Spring context**: Unit tests are pure Mockito — no `@SpringBootTest`

### Running Tests

```bash
./mvnw test
```

---

## 23. Coding Conventions & HFT Rules

### Naming Conventions

| Type | Pattern | Example |
|---|---|---|
| Service | `@Service` + suffix `Service` | `MarketDataEngine`, `VolatilityFilterService` |
| Controller | `@RestController` + suffix `Controller` | `StrategyController` |
| Config | `@Configuration` + `@ConfigurationProperties` + suffix `Config` | `StrategyConfig` |
| DTO | Suffix `Request` / `Response` | `StrategyRequest`, `ApiResponse<T>` |
| Entity | Suffix `Entity` | `TradeEntity` |
| Repository | Suffix `Repository` | `TradeRepository` |
| Enum | Descriptive name | `StrategyType`, `StrategyStatus` |

### Mandatory Patterns

1. **Logging**: Always `@Slf4j` (Lombok). Never `System.out.println`.
2. **User Context**: Use `CurrentUserContext.get()` to access current Kite session.
3. **API Responses**: Return `ApiResponse<T>` from all controllers.
4. **Order Routing**: Use `UnifiedTradingService` (not `TradingService` directly) for all order operations.
5. **Strategy Creation**: Always through `StrategyFactory` — never `new SellATMStraddleStrategy(...)`.
6. **Exit Logic**: Implement `ExitStrategy`, assign priority, register in `PositionMonitorV2`.
7. **Persistence**: Use `TradePersistenceService` (async) — never block strategy threads on DB writes.
8. **Timestamps**: All timestamps in IST (Asia/Kolkata).

### HFT Hot-Path Rules

1. **Use `double` over `Double` / `BigDecimal`** for price arithmetic — avoid autoboxing.
2. **Use `ThreadLocal<SimpleDateFormat>`** — `SimpleDateFormat` is not thread-safe.
3. **Use pre-allocated result objects** (e.g., `ExitResult.NO_EXIT_RESULT`) to avoid GC pressure.
4. **Use `ConcurrentHashMap`** — never `synchronized` on the read path.
5. **Use indexed `for` loops** — avoid `Iterator` allocations in per-tick code.
6. **Use Eclipse Collections** for primitive collections where autoboxing would be costly.
7. **No `new` objects on tick path** — reuse, pre-allocate, or return singletons.

### Concurrency Model

- `ScheduledExecutorService` for background refresh cycles (MarketDataEngine, MarketStateUpdater)
- `CompletableFuture` for parallel order placement
- `ConcurrentHashMap` for all shared caches
- `ThreadLocal` for per-thread mutable state (user context, date formatters)
- Async persistence thread pool with `UserContextPropagatingTaskDecorator`

---

## 24. Common Tasks — Where to Look

| Task | Files to Read First |
|---|---|
| **Add a new exit strategy** | `ExitStrategy.java`, `AbstractExitStrategy.java`, `ExitContext.java`, `ExitResult.java`, `PositionMonitorV2.java` |
| **Add a new trading strategy** | `TradingStrategy.java`, `BaseStrategy.java`, `StrategyFactory.java`, `StrategyType.java` |
| **Change neutral market signals (V2)** | `NeutralMarketDetectorServiceV2.java`, `NeutralMarketConfig.java`, `NeutralMarketResult.java`, `SignalResult.java` |
| **Change neutral market signals (V3)** | `NeutralMarketDetectorServiceV3.java`, `NeutralMarketV3Config.java`, `NeutralMarketResultV3.java`, `Regime.java`, `BreakoutRisk.java` |
| **Add a V3 regime/micro/breakout signal** | `NeutralMarketDetectorServiceV3.java` (add evaluator method + wire in `evaluateAllLayers`), `NeutralMarketV3Config.java` (add weight + threshold) |
| **Wire V3 into strategy execution** | `MarketStateUpdater.java`, `SellATMStraddleStrategy.java`, `StrategyRestartScheduler.java` — replace V2 dependency with V3 |
| **Modify order placement** | `UnifiedTradingService.java`, `PaperTradingService.java`, `TradingService.java` |
| **Add a new config property** | Relevant `*Config.java` class + `application.yml` |
| **Add a new REST endpoint** | Existing controller for reference → new/existing Service → return `ApiResponse<T>` |
| **Add a new entity/table** | `@Entity` class in `entity/` → `JpaRepository` in `repository/` → Flyway SQL in `db/migration/` (prod) |
| **Tune cache refresh rates** | `MarketDataEngineConfig.java`, `application.yml` (`market-data-engine.*`) |
| **Debug delta computation** | `DeltaCacheService.java`, `MarketDataEngine.java` (delta refresh cycle), `BaseStrategy.java` |
| **Add new backtest capability** | `BacktestEngine.java`, `BacktestService.java`, `BacktestController.java` |
| **Modify charges** | `ZerodhaChargeCalculator.java`, `PaperTradingConfig.java` |
| **Change auto-restart behavior** | `StrategyRestartScheduler.java`, `MarketStateUpdater.java`, `DailyPnlGateService.java` |
| **Modify WebSocket tick handling** | `WebSocketService.java`, `PositionMonitorV2.java`, `LegMonitor.java` |
| **Add new persistence data** | `TradePersistenceService.java`, `PersistenceBufferService.java`, new entity + repository |
| **Tune V3 neutral market thresholds** | `NeutralMarketV3Config.java` (weights, regime/micro thresholds, breakout params), `application.yml` (`neutral-market-v3.*`) |
| **Tune V3 breakout risk sensitivity** | `NeutralMarketV3Config.java` (`breakout-tight-range-threshold`, `breakout-edge-proximity-pct`, `breakout-momentum-candles`) |

---

## 25. Domain Vocabulary

| Term | Meaning |
|---|---|
| **ATM** | At-The-Money — strike closest to current NIFTY spot price |
| **OTM** | Out-of-The-Money — strike away from spot (lower for PE, higher for CE) |
| **CE / PE** | Call option / Put option |
| **Delta (Δ)** | Option sensitivity to underlying price change. 0.5 = ATM, 0.1 = far OTM |
| **Gamma** | Rate of delta change (highest at ATM, peaks on expiry day) |
| **Theta** | Time decay — premium lost per day (benefits option sellers) |
| **Vega** | Sensitivity to implied volatility changes |
| **IV** | Implied Volatility — market's expectation of future price movement |
| **India VIX** | India Volatility Index — fear gauge based on NIFTY options |
| **Straddle** | Buy/sell CE + PE at the **same** strike (ATM) |
| **Strangle** | Buy/sell CE + PE at **different** OTM strikes |
| **Hedge leg** | OTM buy option to cap maximum loss (risk protection) |
| **VWAP** | Volume-Weighted Average Price — institutional benchmark |
| **ADX** | Average Directional Index — trend strength indicator (< 20 = ranging) |
| **OI** | Open Interest — total outstanding option contracts |
| **MTM** | Mark-to-Market — unrealised P&L based on current prices |
| **IST** | Indian Standard Time (Asia/Kolkata, UTC+5:30) — all timestamps use this |
| **NSE** | National Stock Exchange of India |
| **NFO** | NSE Futures & Options exchange segment |
| **NIFTY 50** | Index of top 50 NSE-listed companies (underlying for options) |
| **Lot size** | NIFTY = 75 contracts per lot (minimum tradeable unit) |
| **MIS** | Margin Intraday Settlement — intraday product type (auto-squared off at EOD) |
| **Auto square-off** | Forced exit of all positions at configured time (default 15:10 IST) |
| **Neutral market** | Low-volatility, range-bound market — optimal for straddle/strangle entry |
| **Paper trading** | Simulated trading with virtual money (no real orders placed) |
| **HFT** | High-Frequency Trading — architecture optimized for minimal latency |
| **Kite Connect** | Zerodha's REST + WebSocket API for programmatic trading |
| **GTT** | Good-Till-Triggered — persistent conditional orders on Kite |
| **Trailing SL** | Stop loss that moves up with profit (locks in gains) |
| **Regime** | Market classification: STRONG_NEUTRAL, WEAK_NEUTRAL, TRENDING (V2/V3) |
| **Confidence** | Normalized score (0.0–1.0): V2 = score/10, V3 = score/15 |
| **Lot multiplier** | Position sizing: 0× (no trade), 1× (weak), 2× (moderate), 3× (high confidence) |
| **Price oscillation** | Signal: choppy price action measured by close-to-close direction reversals |
| **VWAP pullback** | Signal: mean-reversion pattern — price deviates from VWAP then reverts back |
| **Microstructure** | V3: Real-time tick/candle-level opportunity detection layer |
| **Breakout risk** | V3: Safety gate that blocks entry when tight range + edge proximity + momentum align |
| **Gamma pin** | Expiry-day effect where spot price gravitates toward the highest OI strike |
| **Micro-neutral override** | V3: Allows entry when regime is borderline (score 3) but microstructure signals are very strong (micro ≥ 3) |

---

## 26. Changelog

### Version 6.0 (2026-03-20) — Neutral Market Detector V3 (3-Layer Tradable Opportunity Detector)

**New Files:**
- `NeutralMarketDetectorServiceV3.java` — 3-layer detection engine: Regime (macro neutrality) + Microstructure (real-time opportunity) + Breakout Risk (safety gate). 1286 lines, 11 configurable signals, HFT-safe with 15s TTL caching.
- `NeutralMarketV3Config.java` — `@ConfigurationProperties(prefix = "neutral-market-v3")` with 40+ configurable fields covering all 3 layers.
- `NeutralMarketResultV3.java` — Immutable composite result: tradable, regimeScore (0–9), microScore (0–5), finalScore (0–15), confidence, Regime enum, BreakoutRisk enum, lotMultiplier, signal map, summary. Pre-allocated `disabled()` singleton.
- `Regime.java` — Enum: `STRONG_NEUTRAL`, `WEAK_NEUTRAL`, `TRENDING`
- `BreakoutRisk.java` — Enum: `LOW`, `MEDIUM`, `HIGH`
- `application.yml` — Added `neutral-market-v3:` section with all V3 properties

**Key Design Decisions:**
1. **3-layer architecture** replaces V2's flat scoring — separates "is market neutral?" (regime) from "is now a good entry?" (microstructure) from "is breakout imminent?" (safety gate)
2. **Microstructure layer** (new) detects precise entry moment via VWAP pullback momentum, HF oscillation with amplitude check, and micro range stability
3. **Breakout Risk layer** (new) blocks entry when tight range + edge proximity + momentum buildup align (all 3 = HIGH → block)
4. **Micro-neutral override** — allows trade when regime is borderline (score ≥ 3) but micro signals are very strong (micro ≥ 3), catching opportunities V2 misses
5. **15s cache TTL** (vs V2's 30s) for faster micro-signal responsiveness
6. **Lot multiplier forced to 0** when not tradable — prevents semantic mismatch
7. **Breakout momentum** uses strict inequality — flat candles don't falsely kill directional momentum
8. **VWAP pullback** uses current spot price for live validation + 0.02% tolerance for noise resilience

**Code Review Fixes Applied (from production HFT audit):**
- Fixed breakout momentum detection: flat candle bug (strict inequality instead of `<=`/`>=`)
- Fixed lot multiplier: now 0 when `tradable == false` (was score-based regardless)
- Fixed VWAP pullback: now uses `spotPrice` parameter for live reversion validation (was unused dead parameter)
- Fixed VWAP pullback: added noise tolerance (0.02%) to avoid overly strict monotonic requirement
- Added config-driven `microNeutralOverrideRegimeThreshold` / `microNeutralOverrideMicroThreshold` (was hardcoded `== 3`)
- Removed redundant `adxValues == null` check (V1's `computeADXSeries` never returns null)
- Replaced `RuntimeException` in `resolveInstrumentToken` with graceful error handling

**Integration Status:**
- V3 is compiled and available as a Spring `@Service` bean
- NOT yet wired into active strategy execution (V2 remains the active consumer in `MarketStateUpdater`, `SellATMStraddleStrategy`, `StrategyRestartScheduler`)
- Can be tested via direct `evaluate("NIFTY")` call
- No unit tests yet (to be added)

**Known Limitations:**
- Gamma Pin signal still uses `tradingService.getQuote()` (direct API call) for OI data on expiry days
- MEDIUM breakout risk currently has no effect on tradability (only HIGH blocks)
- `String.format()` calls in `log.debug()` statements are not guarded by `log.isDebugEnabled()`
- Multiple `ZonedDateTime.now(IST)` calls per evaluation cycle (could be consolidated)

### Version 5.0 (2026-03-20) — Neutral Market Detector V2

**New Files:**
- `NeutralMarketDetectorServiceV2.java` — Weighted confidence scoring engine with 6 signals, regime classification, per-instrument caching
- `NeutralMarketResult.java` — Immutable result model with score (0–10), confidence (0.0–1.0), regime, tradable flag, per-signal breakdown
- `SignalResult.java` — Immutable record for individual signal evaluation results
- `NeutralMarketDetectorServiceV2Test.java` — 24 unit tests covering all signals, regimes, time adaptation, caching, backward compatibility

**Modified Files:**
- `NeutralMarketConfig.java` — Added 14 V2 config properties (oscillation, pullback, time adaptation, weights, regime thresholds)
- `MarketStateUpdater.java` — Now uses `NeutralMarketDetectorServiceV2` instead of V1
- `SellATMStraddleStrategy.java` — Now uses V2 detector for pre-flight neutral market check
- `StrategyRestartScheduler.java` — Now uses V2 detector for restart gating

**Key Design Decisions:**
1. **Weighted scoring** (0–10) replaces binary +2/signal — allows nuanced regime classification
2. **Three regimes** (STRONG_NEUTRAL / WEAK_NEUTRAL / TRENDING) replace binary pass/fail
3. **Premium Decay signal removed** — too lagging for intraday use
4. **Gamma Pin** now expiry-day-only — avoids noise on non-expiry days
5. **Time-based adaptation** — opening session stricter (-1), pre-close more lenient (+1)
6. **Per-instrument ConcurrentHashMap** cache replaces single-slot AtomicReference
7. **Error results cached with ~5s TTL** — enables fast recovery from transient API failures
8. **Instrument token resolution cached** — avoids repeated NSE instrument list scans
9. **MDE-first expiry lookup** — uses `getNearestWeeklyExpiry()` before falling back to NFO instrument scan
10. **Logging reduced** — per-signal detail at DEBUG, single INFO summary line per 30s cycle

**Known Limitation:**
- Gamma Pin signal still uses `tradingService.getQuote()` (direct API call) for OI data — to be migrated to `MarketDataEngine` in a future release

---

*This document is auto-generated from codebase analysis. Keep it updated when making structural changes to the project.*

