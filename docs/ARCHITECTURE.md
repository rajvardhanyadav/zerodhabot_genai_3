# Zerodha Trading Bot - Architecture Overview

Last updated: 2025-12-10 (IST)

This document captures the high-level architecture, key modules, data flows, and extension points of the backend.
It’s meant to preserve shared context for future contributors and to accelerate onboarding and changes.

## 1. What this app is
- Spring Boot backend integrating Zerodha Kite Connect for options trading.
- Exposes REST APIs for UI to perform login, orders, portfolio, market data, strategies, monitoring, paper trading, GTT, and historical replay.
- Supports both Live trading and Paper trading via a unified routing layer.
- Multi-tenant by design: every request is processed in the context of a specific user identified by the `X-User-Id` header.
- **Persistence layer** for storing daily trading data including trades, strategy executions, order timing metrics, delta snapshots, and daily P&L summaries.

## 2. Runtime modes
- Live: Uses `KiteConnect` directly for orders, positions, trades, etc.
- Paper: Uses in-memory simulation (`PaperTradingService`) with margin, brokerage/taxes, slippage and delays.
- Routing: `UnifiedTradingService` decides based on `trading.paper-trading-enabled` in `application.yml` (reads current user via request context).

## 3. Key packages and responsibilities
- `config/`
  - `KiteConfig`: Loads credentials; provides base settings (API key/secret). Per-user sessions are managed by `UserSessionManager` (not a global singleton connection).
  - `PaperTradingConfig`: Paper mode flags and economics (charges, slippage, delay, rejection probability).
  - `StrategyConfig`: Defaults for stop-loss and target; autosquare-off options (reserved).
  - `PersistenceConfig`: Data retention settings and cleanup job configuration.
  - `AsyncPersistenceConfig`: Dedicated thread pool for asynchronous persistence operations (non-blocking for HFT).
  - `SwaggerConfig`, `SwaggerGlobalHeaderConfig`: OpenAPI setup and global header parameter injection for `X-User-Id`.
  - `UserContextFilter`: Extracts `X-User-Id` from incoming requests and stores it in `CurrentUserContext` (ThreadLocal) for downstream services.
  - `CorsConfig`.

- `controller/`
  - `AuthController`: login URL, session generation, profile (per-user session creation; requires `X-User-Id`).
  - `OrderController`: place/modify/cancel orders, list orders/history, order charges.
  - `PortfolioController`: positions, holdings (live only), trades (live only), convert position, day P&L.
  - `MarketDataController`: quote/ohlc/ltp/historical data, instruments.
  - `GTTController`: list/place/get/modify/cancel GTTs.
  - `StrategyController`: execute strategy, list active, get details by id, stop one/all, list types, instruments, expiries, bot status.
  - `MonitoringController`: per-user WebSocket connect/disconnect/status, stop monitoring for id. Connect requires a valid per-user access token.
  - `HealthController`: liveness endpoint.
  - `BacktestController`: Runs single or batch backtests and exposes execution history/health endpoints.
  - `TradingHistoryController`: Query persisted trade history, strategy executions, and daily P&L summaries.

- `entity/` (NEW - JPA Entities)
  - `TradeEntity`: Individual trade records with entry/exit prices, timestamps, P&L, and charges.
  - `StrategyExecutionEntity`: Strategy execution lifecycle with order legs.
  - `OrderLegEntity`: Individual legs within a strategy (CE/PE).
  - `DeltaSnapshotEntity`: Greeks/Delta snapshots for analysis.
  - `DailyPnLSummaryEntity`: Aggregated daily P&L, win rate, trade statistics.
  - `PositionSnapshotEntity`: End-of-day position snapshots.
  - `OrderTimingEntity`: HFT latency metrics for order placements.

- `repository/` (NEW - Spring Data JPA Repositories)
  - `TradeRepository`, `StrategyExecutionRepository`, `OrderLegRepository`
  - `DeltaSnapshotRepository`, `DailyPnLSummaryRepository`
  - `PositionSnapshotRepository`, `OrderTimingRepository`

- `service/`
  - `UserSessionManager`: Manages per-user `KiteConnect` sessions (create/replace/invalidate) keyed by `X-User-Id`.
  - `TradingService` (live): Thin wrapper around `KiteConnect` for all live operations; resolves the current user's `KiteConnect` via `UserSessionManager`.
  - `UnifiedTradingService`: Routes calls to live vs paper, converts paper types to Kite-like DTOs, computes day P&L; always reads the current user from `CurrentUserContext`.
  - `PaperTradingService` (paper): In-memory order execution, positions, account P&L, brokerage/taxes, simple price fetch via LTP; all state is keyed per user. Now also persists trades asynchronously.
  - `BotStatusService`: Holds an in-memory `RUNNING`/`STOPPED` status with `lastUpdated`; flipped on `/api/strategies/execute` and `/api/strategies/stop-all`.
  - `persistence/TradePersistenceService` (NEW): Asynchronous persistence of trades, strategy executions, delta snapshots, positions, and order timing. Uses write-behind pattern to avoid blocking HFT hot paths.
  - `persistence/DataCleanupService` (NEW): Scheduled job to clean up old data based on retention policies.
  - `strategy/*`:
    - `StrategyService`: lifecycle, registry of active executions, exits for stop/stop-all, instruments meta, expiries. Now persists strategy executions on completion.
    - `StrategyFactory`: returns implementation for `ATM_STRADDLE`, `SELL_ATM_STRADDLE`.
    - `BaseStrategy`: shared helpers (spot price, ATM/delta calc w/ BS + IV estimation, lot sizes, instruments filter, order creation, entry price lookup).
    - `ATMStraddleStrategy`: two-leg option buy strategy (Buy 1 ATM Call + Buy 1 ATM Put) with monitoring and exits.
    - `SellATMStraddleStrategy`: two-leg option sell strategy (Sell 1 ATM Call + Sell 1 ATM Put) with monitoring and exits.
    - `monitoring/WebSocketService`: Per-user KiteTicker connections, per-user instrument subscriptions and resubscription on reconnect, routes live ticks to that user's monitors.
    - `monitoring/PositionMonitor`: Tracks legs, prices, triggers exits and callbacks.

- `dto/` and `model/`
  - `StrategyRequest`, `StrategyExecutionResponse`, `OrderRequest`, `OrderResponse`, `DayPnLResponse`, etc.
  - `StrategyExecution`, `StrategyStatus`, `StrategyType`.
  - `BotStatusResponse`, `BotStatus`.

- `util/` and `service/TradingConstants`
  - Centralized constants for exchanges, products, order types/status, messages, and strategy messages.
  - `CurrentUserContext`: ThreadLocal per-request user id used by services to resolve per-user state.

## 4. Strategy execution and monitoring flow
1) UI calls `POST /api/strategies/execute` with `StrategyRequest` (include `X-User-Id`).
2) `StrategyService` creates `executionId` and selects a strategy via `StrategyFactory`.
3) Strategy implementation (e.g., Straddle):
   - Gets spot price and instruments, computes ATM or OTM strikes.
   - Places two BUY orders via `UnifiedTradingService` (routes to paper or live) for the current user.
   - Looks up entry prices from order history.
   - Creates a `PositionMonitor` with legs and sets:
     - Exit ALL legs callback (SL/Target/threshold) -> place SELL market orders for both legs.
     - Exit INDIVIDUAL leg callback (per-leg loss threshold) -> place SELL market for that leg.
   - Registers monitor in the per-user `WebSocketService` (live ticks) or gets synthetic ticks from historical replay.
4) `PositionMonitor` thresholds (defaults in code):
   - All-legs exit at +3.0 points price diff on any leg.
   - Individual leg exit at -1.5 points price diff.
   - Price diff = current - entry price.
5) Stops monitoring once exits complete; `StrategyService` marks execution status accordingly.

## 5. Configuration
- `application.yml` keys of interest:
  - `kite.api-key`, `kite.api-secret` (recommend using env vars; do not commit secrets).
  - `trading.paper-trading-enabled` (switch between paper and live).
  - Charges/fees/slippage/delay/rejection parameters under `trading.*`.
  - `strategy.default-stop-loss-points`, `strategy.default-target-points`, and square-off flags.
  - Swagger UI at `/swagger-ui.html` (OpenAPI at `/api-docs`) with global header parameter `X-User-Id`.

## 6. Exposed API surface (by group)
- Auth: `/api/auth/*` (`/api/auth/session` does NOT require `X-User-Id`; if absent the Kite `user.userId` is inferred and returned. Subsequent protected calls must include `X-User-Id`.)
- Orders: `/api/orders/*`
- Portfolio: `/api/portfolio/*`
- Market Data: `/api/market/*`
- Account: `/api/account/*`
- GTT: `/api/gtt/*`
- Strategies: `/api/strategies/*` (includes `/api/strategies/bot-status`)
- Monitoring: `/api/monitoring/*` (connect/disconnect/status are per-user)
- Paper trading: `/api/paper-trading/*`
- **Trading History: `/api/history/*`** (NEW - trades, strategy executions, daily summaries)
- Health: `/api/health`

For full request/response payloads, see `COMPLETE_API_DOCUMENTATION.md` and Swagger UI.

## 7. Multi-user and request scoping
- The header `X-User-Id` must be provided on protected endpoints (all except initial `/api/auth/session`).
- If `/api/auth/session` is called without the header, the backend stores the session under the Kite `user.userId` and returns that id to the client; the client must then use it in `X-User-Id` for subsequent requests.
- `UserSessionManager` maintains one `KiteConnect` session per user id key.
- `WebSocketService` keeps per-user ticker connections, subscriptions, and monitors; reconnects resubscribe per user only.
- `PaperTradingService` stores accounts, orders, and positions per user; resetting one user doesn’t affect others.
- Swagger adds the `X-User-Id` parameter globally so it can be set once in the UI.

## 8. Extension points (recommended next steps)
- Implement additional strategies: Bull Call Spread, Bear Put Spread, Iron Condor.
- Make monitor thresholds configurable (global + request-level override).
- Paper mode enhancements: add trades tracking and partial fill/slippage models.
- Security hygiene: remove fallback defaults for Kite API key/secret from `application.yml` and rely solely on environment variables.
- Improve test coverage: unit tests for strategies and routing.

## 9. Quickstart (dev)
- Build (Windows): `./mvnw.cmd -DskipTests=false test`
- Build (macOS/Linux): `./mvnw -DskipTests=false test`
- Run (Windows): `./mvnw.cmd spring-boot:run`
- Run (macOS/Linux): `./mvnw spring-boot:run`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Toggle paper/live: set `trading.paper-trading-enabled` in `application.yml` or via env var.

## 10. Persistence Layer (NEW)

### Database Configuration
- **Development**: H2 in-memory/file database (`jdbc:h2:file:./data/tradingbot`)
- **Production**: PostgreSQL with connection pooling via HikariCP
- **Migrations**: Flyway for schema versioning (`src/main/resources/db/migration/`)

### Entities Persisted
| Entity | Description | Retention |
|--------|-------------|-----------|
| `TradeEntity` | Individual trades with entry/exit, P&L, charges | 365 days |
| `StrategyExecutionEntity` | Strategy lifecycle with order legs | 365 days |
| `DeltaSnapshotEntity` | Greeks/IV snapshots for analysis | 90 days |
| `DailyPnLSummaryEntity` | Daily aggregated P&L and statistics | 365 days |
| `PositionSnapshotEntity` | EOD position snapshots | 180 days |
| `OrderTimingEntity` | HFT latency metrics | 90 days |

### Async Persistence (HFT Optimized)
- All persistence operations use `@Async` with a dedicated thread pool (`persistenceExecutor`)
- Write-behind caching pattern ensures trading hot path is not blocked
- Default thread pool: 4-8 threads with 500 operation queue capacity

### Data Cleanup
- Scheduled job runs daily at 2 AM (configurable via `persistence.cleanup.cron`)
- Automatically removes data older than configured retention periods
- Can be disabled via `persistence.cleanup.enabled=false`

### Configuration Keys
```yaml
persistence:
  enabled: true
  retention:
    trades-days: 365
    delta-snapshots-days: 90
    position-snapshots-days: 180
    order-timing-days: 90
  cleanup:
    enabled: true
    cron: "0 0 2 * * ?"
```

## 11. Troubleshooting
- **Common issues**:
  - 401 Unauthorized: Missing or invalid `X-User-Id` header. Ensure it’s set for all protected endpoints.
  - 404 Not Found: Check if the endpoint is correct and requires authentication.
  - 500 Internal Server Error: Check server logs for stack traces; common in strategy execution errors.

- **Debugging tips**:
  - Enable debug logging for `com.zerodhatradingbot` packages to trace request handling.
  - Check database connectivity and migrations if encountering persistence errors.
  - Use Postman or curl to manually test and debug API requests/responses.

- **Log locations**:
  - Application logs: `logs/trading-bot.log`
  - Access logs: `logs/access.log`
  - Error logs: `logs/error.log`

## 12. Glossary
- ATM: At-The-Money. OTM: Out-Of-The-Money.
- CE/PE: Call/Put European style options.
- SL/Target: Stop-Loss/Target.
- LTP/Quote/OHLC: Market data quote types from Kite.
