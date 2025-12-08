# Zerodha Trading Bot - Architecture Overview

Last updated: 2025-11-23 (IST)

This document captures the high-level architecture, key modules, data flows, and extension points of the backend.
It’s meant to preserve shared context for future contributors and to accelerate onboarding and changes.

## 1. What this app is
- Spring Boot backend integrating Zerodha Kite Connect for options trading.
- Exposes REST APIs for UI to perform login, orders, portfolio, market data, strategies, monitoring, paper trading, GTT, and historical replay.
- Supports both Live trading and Paper trading via a unified routing layer.
- Multi-tenant by design: every request is processed in the context of a specific user identified by the `X-User-Id` header.

## 2. Runtime modes
- Live: Uses `KiteConnect` directly for orders, positions, trades, etc.
- Paper: Uses in-memory simulation (`PaperTradingService`) with margin, brokerage/taxes, slippage and delays.
- Routing: `UnifiedTradingService` decides based on `trading.paper-trading-enabled` in `application.yml` (reads current user via request context).

## 3. Key packages and responsibilities
- `config/`
  - `KiteConfig`: Loads credentials; provides base settings (API key/secret). Per-user sessions are managed by `UserSessionManager` (not a global singleton connection).
  - `PaperTradingConfig`: Paper mode flags and economics (charges, slippage, delay, rejection probability).
  - `StrategyConfig`: Defaults for stop-loss and target; autosquare-off options (reserved).
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
  - `HistoricalController`: execute a strategy with historical replay (paper mode).
  - `HealthController`: liveness endpoint.
  - `BacktestController`: Runs single or batch backtests and exposes execution history/health endpoints.

- `service/`
  - `UserSessionManager`: Manages per-user `KiteConnect` sessions (create/replace/invalidate) keyed by `X-User-Id`.
  - `TradingService` (live): Thin wrapper around `KiteConnect` for all live operations; resolves the current user’s `KiteConnect` via `UserSessionManager`.
  - `UnifiedTradingService`: Routes calls to live vs paper, converts paper types to Kite-like DTOs, computes day P&L; always reads the current user from `CurrentUserContext`.
  - `PaperTradingService` (paper): In-memory order execution, positions, account P&L, brokerage/taxes, simple price fetch via LTP; all state is keyed per user (accounts, positions, order history).
  - `HistoricalDataService`: Minute candles -> per-second linear interpolation in IST trading window.
  - `HistoricalReplayService`: Executes a strategy in paper mode, disables live WS temporarily, fetches second-wise prices for legs, replays as synthetic ticks to monitor.
  - `BotStatusService`: Holds an in-memory `RUNNING`/`STOPPED` status with `lastUpdated`; flipped on `/api/strategies/execute` and `/api/strategies/stop-all`.
  - `strategy/*`:
    - `StrategyService`: lifecycle, registry of active executions, exits for stop/stop-all, instruments meta, expiries.
    - `StrategyFactory`: returns implementation for `ATM_STRADDLE`, `SELL_ATM_STRADDLE`.
    - `BaseStrategy`: shared helpers (spot price, ATM/delta calc w/ BS + IV estimation, lot sizes, instruments filter, order creation, entry price lookup).
    - `ATMStraddleStrategy`: two-leg option buy strategy (Buy 1 ATM Call + Buy 1 ATM Put) with monitoring and exits.
    - `SellATMStraddleStrategy`: two-leg option sell strategy (Sell 1 ATM Call + Sell 1 ATM Put) with monitoring and exits.
    - `monitoring/WebSocketService`: Per-user KiteTicker connections, per-user instrument subscriptions and resubscription on reconnect, routes live ticks to that user's monitors; can disable live subscription during historical replay.
    - `monitoring/PositionMonitor`: Tracks legs, prices, triggers exits and callbacks.
  - `BacktestingService`: Spins up strategy executions against historical data, drives PositionMonitor with replay ticks, and stores metrics / trade timelines.
  - `BatchBacktestingService`: Parallel/sequential orchestration for multiple backtests plus aggregate stats.

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

## 5. Historical replay (paper mode only)
- Determines latest trading day [09:15–15:30 IST], fetches minute candles from Kite.
- Expands to per-second via linear interpolation.
- Feeds synthetic prices to the `PositionMonitor`.
- Temporarily disables live WebSocket subscription while registering monitoring, then re-enables afterwards. Replay runs asynchronously.
- Replay speed is configurable via `historical.replay.sleep-millis-per-second` (0 for fastest).
- Backtesting builds on this pipeline but precomputes full metrics, leg details, and trade timelines; see Section 6.

## 6. Backtesting flow
1) Client posts to `/api/backtest/execute` (or batch equivalent) with strategy params + optional `backtestDate`.
2) `BacktestingService` validates paper-trading mode, determines trading day window (latest prior day if none provided), and converts the request into a regular `StrategyRequest`.
3) Live WebSocket subscriptions pause; strategy execution runs in paper mode to register legs and monitors.
4) Historical second-wise prices load for each leg via `HistoricalDataService`; `PositionMonitor` receives synthetic ticks at configured replay speed.
5) Monitor exits (SL/Target/price diff) fire exactly as in live trading; completion reason is captured from `StrategyExecution`.
6) Replay captures trade events (ENTRY, periodic PRICE_UPDATE, EXIT), leg P&L, and performance metrics (premium paid/received, gross/net P&L, ROI, charges, drawdown, holding duration, trade count).
7) Response is cached under `backtestId`; `/api/backtest/{id}` can retrieve it later. Batch executions aggregate totals/win rate/best-worst returns, using the same engine per request.

## 7. Configuration
- `application.yml` keys of interest:
  - `kite.api-key`, `kite.api-secret` (recommend using env vars; do not commit secrets).
  - `trading.paper-trading-enabled` (switch between paper and live).
  - Charges/fees/slippage/delay/rejection parameters under `trading.*`.
  - `strategy.default-stop-loss-points`, `strategy.default-target-points`, and square-off flags.
  - Backtesting toggles under `backtesting.*` (enable flag, replay speed default, detailed log flag, batch executor sizing) plus requirement that `trading.paper-trading-enabled=true`.
  - Swagger UI at `/swagger-ui.html` (OpenAPI at `/api-docs`) with global header parameter `X-User-Id`.

## 8. Exposed API surface (by group)
- Auth: `/api/auth/*` (`/api/auth/session` does NOT require `X-User-Id`; if absent the Kite `user.userId` is inferred and returned. Subsequent protected calls must include `X-User-Id`.)
- Orders: `/api/orders/*`
- Portfolio: `/api/portfolio/*`
- Market Data: `/api/market/*`
- Account: `/api/account/*`
- GTT: `/api/gtt/*`
- Strategies: `/api/strategies/*` (includes `/api/strategies/bot-status`)
- Monitoring: `/api/monitoring/*` (connect/disconnect/status are per-user)
- Paper trading: `/api/paper-trading/*`
- Health: `/api/health`
- Backtesting: `/api/backtest/execute`, `/api/backtest/batch`, `/api/backtest/{backtestId}`, `/api/backtest/health`.

For full request/response payloads, see `COMPLETE_API_DOCUMENTATION.md` and Swagger UI.

## 9. Multi-user and request scoping
- The header `X-User-Id` must be provided on protected endpoints (all except initial `/api/auth/session`).
- If `/api/auth/session` is called without the header, the backend stores the session under the Kite `user.userId` and returns that id to the client; the client must then use it in `X-User-Id` for subsequent requests.
- `UserSessionManager` maintains one `KiteConnect` session per user id key.
- `WebSocketService` keeps per-user ticker connections, subscriptions, and monitors; reconnects resubscribe per user only.
- `PaperTradingService` stores accounts, orders, and positions per user; resetting one user doesn’t affect others.
- Swagger adds the `X-User-Id` parameter globally so it can be set once in the UI.

## 10. Extension points (recommended next steps)
- Implement additional strategies: Bull Call Spread, Bear Put Spread, Iron Condor.
- Make monitor thresholds configurable (global + request-level override).
- Paper mode enhancements: add trades tracking and partial fill/slippage models.
- Security hygiene: remove fallback defaults for Kite API key/secret from `application.yml` and rely solely on environment variables.
- Improve test coverage: unit tests for strategies, routing, and historical replay.

## 11. Quickstart (dev)
- Build (Windows): `./mvnw.cmd -DskipTests=false test`
- Build (macOS/Linux): `./mvnw -DskipTests=false test`
- Run (Windows): `./mvnw.cmd spring-boot:run`
- Run (macOS/Linux): `./mvnw spring-boot:run`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Toggle paper/live: set `trading.paper-trading-enabled` in `application.yml` or via env var.

## 12. Glossary
- ATM: At-The-Money. OTM: Out-Of-The-Money.
- CE/PE: Call/Put European style options.
- SL/Target: Stop-Loss/Target.
- LTP/Quote/OHLC: Market data quote types from Kite.
