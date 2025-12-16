# Zerodha Trading Bot - Technical Documentation

**Version 4.2** | **December 2025**

> 🚀 **Quick Start**: See [README.md](../README.md)

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Package Structure](#2-package-structure)
3. [Runtime Modes](#3-runtime-modes)
4. [Multi-User Model](#4-multi-user-model)
5. [Trading Strategies](#5-trading-strategies)
6. [Position Monitoring](#6-position-monitoring)
7. [P&L Calculation](#7-pl-calculation)
8. [Data Persistence](#8-data-persistence)
9. [Configuration Reference](#9-configuration-reference)
10. [API Reference](#10-api-reference)
11. [Extension Points](#11-extension-points)

---

## 1. Architecture Overview

Spring Boot backend integrating Zerodha Kite Connect for options trading.

```
┌─────────────────────────────────────────────────────────────────┐
│                        REST Controllers                          │
├─────────────────────────────────────────────────────────────────┤
│  Auth │ Orders │ Portfolio │ Market │ Strategies │ Monitoring   │
└───────────────────────────┬─────────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────────┐
│                    UnifiedTradingService                         │
│              (Routes to Live or Paper mode)                      │
└───────────────────────────┬─────────────────────────────────────┘
                            │
        ┌───────────────────┴───────────────────┐
        ▼                                       ▼
┌───────────────────┐                 ┌───────────────────┐
│  TradingService   │                 │ PaperTradingService│
│   (Kite Connect)  │                 │  (In-memory sim)   │
└───────────────────┘                 └───────────────────┘
```

---

## 2. Package Structure

```
com.tradingbot/
├── config/
│   ├── KiteConfig              # Kite API credentials
│   ├── PaperTradingConfig      # Paper mode settings
│   ├── StrategyConfig          # Strategy defaults
│   ├── PersistenceConfig       # Data retention
│   ├── AsyncPersistenceConfig  # Async thread pool
│   ├── PnLConfig               # P&L calculation mode
│   ├── UserContextFilter       # X-User-Id extraction
│   └── SwaggerConfig           # OpenAPI setup
│
├── controller/
│   ├── AuthController          # Login, session, profile
│   ├── OrderController         # Order CRUD
│   ├── PortfolioController     # Positions, holdings
│   ├── MarketDataController    # Quotes, OHLC, LTP
│   ├── StrategyController      # Strategy execution
│   ├── MonitoringController    # WebSocket management
│   ├── PaperTradingController  # Paper mode APIs
│   ├── TradingHistoryController# Historical data
│   ├── GTTController           # GTT orders
│   └── HealthController        # Health check
│
├── service/
│   ├── UserSessionManager      # Per-user Kite sessions
│   ├── TradingService          # Live trading via Kite
│   ├── PaperTradingService     # Simulated trading
│   ├── UnifiedTradingService   # Routes live/paper
│   ├── BotStatusService        # RUNNING/STOPPED status
│   ├── LogoutService           # Comprehensive user logout (v4.2)
│   ├── strategy/
│   │   ├── StrategyService     # Lifecycle management
│   │   ├── StrategyFactory     # Strategy instantiation
│   │   ├── BaseStrategy        # Common helpers
│   │   ├── ATMStraddleStrategy # Buy straddle
│   │   └── SellATMStraddleStrategy # Sell straddle
│   ├── monitoring/
│   │   ├── WebSocketService    # Per-user KiteTicker
│   │   └── PositionMonitor     # P&L tracking, exits
│   ├── pnl/
│   │   ├── PnLCalculationStrategy    # Interface
│   │   ├── FixedPnLCalculationStrategy   # Fixed mode
│   │   └── DynamicPnLCalculationStrategy # Dynamic mode
│   └── persistence/
│       ├── TradePersistenceService # Async persistence
│       └── DataCleanupService      # Retention cleanup
│
├── entity/
│   ├── TradeEntity             # Trade records
│   ├── StrategyExecutionEntity # Strategy lifecycle
│   ├── OrderLegEntity          # Strategy legs
│   ├── DailyPnLSummaryEntity   # Daily P&L
│   ├── DeltaSnapshotEntity     # Greeks snapshots
│   ├── PositionSnapshotEntity  # EOD snapshots
│   └── OrderTimingEntity       # Latency metrics
│
└── util/
    ├── CurrentUserContext      # ThreadLocal user ID
    └── TradingConstants        # Constants
```

---

## 3. Runtime Modes

### Live Mode
- Uses `KiteConnect` SDK directly
- Real orders, positions, and market data
- Set `trading.paper-trading-enabled: false`

### Paper Mode
- In-memory simulation via `PaperTradingService`
- Virtual balance, brokerage, slippage simulation
- Set `trading.paper-trading-enabled: true`

### Runtime Toggle
```http
POST /api/paper-trading/mode?paperTradingEnabled=false
```

---

## 4. Multi-User Model

Every request requires `X-User-Id` header (except `/api/auth/session`).

### Per-User Isolation
- **Sessions**: `UserSessionManager` maintains per-user KiteConnect
- **WebSocket**: Per-user KiteTicker connections
- **Paper Trading**: Isolated accounts, orders, positions
- **Monitoring**: Scoped PositionMonitors

### Session Creation
```http
POST /api/auth/session
Content-Type: application/json

{"requestToken": "your_request_token"}
```

If `X-User-Id` header is omitted, userId is derived from Kite profile.

---

## 5. Trading Strategies

### Execution Flow
1. `POST /api/strategies/execute` with StrategyRequest
2. StrategyService creates executionId, selects strategy
3. Strategy fetches spot price, computes ATM strikes
4. Places orders via UnifiedTradingService
5. Creates PositionMonitor with SL/Target
6. Subscribes to WebSocket for price updates
7. Monitors and executes exits based on triggers

### Available Strategies

| Strategy | Direction | Description |
|----------|-----------|-------------|
| `ATM_STRADDLE` | LONG | Buy ATM Call + Put |
| `SELL_ATM_STRADDLE` | SHORT | Sell ATM Call + Put |

### Exit Conditions (Priority Order)
1. **Cumulative Target** - Total P&L ≥ target points → Exit all
2. **Individual Leg SL** - Leg P&L ≤ -5 points → Exit that leg
3. **Trailing Stop** - If activated and hit → Exit all
4. **Fixed Cumulative SL** - Total P&L ≤ -stopLoss → Exit all

---

## 6. Position Monitoring

### WebSocket Architecture
```
KiteTicker (Per User)
       ↓
  Price Ticks
       ↓
  PositionMonitor.updatePrice()
       ↓
  Check Exit Conditions
       ↓
  Execute Exits (if triggered)
```

### Monitor Features
- Real-time price tracking from WebSocket
- Multi-leg support (CE + PE)
- Cumulative P&L calculation
- Configurable SL/Target/Trailing stops
- Callbacks for exit events

---

## 7. P&L Calculation

### Modes

| Mode | Description | Use Case |
|------|-------------|----------|
| `FIXED` (default) | Full position at entry price | Simple strategies, atomic exits |
| `DYNAMIC` | Tracks partial exits | Advanced strategies |

### Configuration
```yaml
pnl:
  calculation-mode: FIXED  # or DYNAMIC
  enable-per-leg-tracking: false
  enable-debug-logging: false
```

### FIXED Mode Formula
```
P&L = (currentPrice - entryPrice) * originalQuantity * direction
```

### DYNAMIC Mode
Tracks realized + unrealized P&L with partial exit support.

---

## 8. Data Persistence

### Async Write-Behind Pattern
- All persistence uses `@Async` with dedicated thread pool
- Trading hot path is NOT blocked
- HFT-optimized for low latency

### Entities & Retention

| Entity | Description | Retention |
|--------|-------------|-----------|
| TradeEntity | Trade records with P&L | 365 days |
| StrategyExecutionEntity | Strategy lifecycle | 365 days |
| DeltaSnapshotEntity | Greeks snapshots | 90 days |
| DailyPnLSummaryEntity | Daily aggregates | 365 days |
| PositionSnapshotEntity | EOD snapshots | 180 days |
| OrderTimingEntity | Latency metrics | 90 days |

### Cleanup Job
Runs daily at 2 AM, configurable via `persistence.cleanup.cron`.

---

## 9. Configuration Reference

### application.yml

```yaml
# Kite Connect
kite:
  api-key: ${KITE_API_KEY}
  api-secret: ${KITE_API_SECRET}

# Trading Mode
trading:
  paper-trading-enabled: true
  initial-balance: 1000000.0
  apply-brokerage-charges: true
  brokerage-per-order: 20.0
  stt-percentage: 0.025
  slippage-percentage: 0.05
  execution-delay-ms: 500
  enable-order-rejection: false

# Strategy Defaults
strategy:
  default-stop-loss-points: 2.0
  default-target-points: 2.0
  trailing-stop-enabled: false
  auto-square-off-enabled: false
  auto-square-off-time: "15:15"

# P&L Calculation
pnl:
  calculation-mode: FIXED
  enable-per-leg-tracking: false

# Persistence
persistence:
  enabled: true
  retention:
    trades-days: 365
    delta-snapshots-days: 90
    position-snapshots-days: 180
  cleanup:
    enabled: true
    cron: "0 0 2 * * ?"

# Database
spring:
  datasource:
    url: jdbc:h2:file:./data/tradingbot
    username: sa
    password:
  jpa:
    hibernate:
      ddl-auto: update

# Logging
logging:
  level:
    com.tradingbot: DEBUG
```

---

## 10. API Reference

### Common Headers
```http
X-User-Id: your-user-id
Content-Type: application/json
```

### Response Format
```json
{
  "success": true,
  "message": "Success",
  "data": { }
}
```

---

### Authentication

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/auth/login-url` | Get Kite OAuth URL |
| POST | `/api/auth/session` | Generate session (X-User-Id optional) |
| GET | `/api/auth/profile` | Get user profile |
| POST | `/api/auth/logout` | Logout and cleanup all resources |

**Generate Session:**
```http
POST /api/auth/session
{"requestToken": "your_token"}
```

**Logout (v4.2):**

Performs comprehensive cleanup of all user-related resources:
- Stops all active trading strategies (closes open positions)
- **Clears strategy execution registry** (prevents stale data in APIs)
- Cancels scheduled strategy auto-restarts
- Disconnects WebSocket connections and clears subscriptions
- Resets paper trading state (orders, positions, accounts)
- Invalidates the Kite session

```http
POST /api/auth/logout
X-User-Id: your-user-id
```

**Response:**
```json
{
  "success": true,
  "message": "Logout successful",
  "data": {
    "userId": "ABC123",
    "strategiesStopped": 2,
    "scheduledRestartsCancelled": 1,
    "webSocketDisconnected": true,
    "paperTradingReset": true,
    "sessionInvalidated": true,
    "durationMs": 145
  }
}
```

> **Idempotency:** Multiple logout calls are safe. Concurrent logout calls for the same user are serialized using per-user locks.

---

### Orders

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/orders` | Place order |
| PUT | `/api/orders/{orderId}` | Modify order |
| DELETE | `/api/orders/{orderId}` | Cancel order |
| GET | `/api/orders` | List all orders |
| GET | `/api/orders/{orderId}/history` | Order history |
| GET | `/api/orders/trades` | Get trades |
| GET | `/api/orders/charges` | Get order charges |

**Place Order:**
```json
{
  "tradingSymbol": "NIFTY24DEC50000CE",
  "exchange": "NFO",
  "transactionType": "BUY",
  "orderType": "MARKET",
  "quantity": 50,
  "product": "NRML"
}
```

---

### Portfolio

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/portfolio/positions` | Get positions |
| GET | `/api/portfolio/holdings` | Get holdings |
| POST | `/api/portfolio/convert` | Convert position |

---

### Market Data

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/market/quote?instruments=NFO:SYMBOL` | Get quote |
| GET | `/api/market/ohlc?instruments=NFO:SYMBOL` | Get OHLC |
| GET | `/api/market/ltp?instruments=NFO:SYMBOL` | Get LTP |
| GET | `/api/market/historical` | Get historical data |
| GET | `/api/market/instruments` | Get all instruments |
| GET | `/api/market/instruments/{exchange}` | Get by exchange |

---

### Strategies

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/strategies/execute` | Execute strategy |
| GET | `/api/strategies/active` | Get active strategies |
| GET | `/api/strategies/{executionId}` | Get strategy details |
| DELETE | `/api/strategies/{executionId}` | Stop strategy |
| DELETE | `/api/strategies/stop-all` | Stop all strategies |
| GET | `/api/strategies/types` | Get strategy types |
| GET | `/api/strategies/instruments` | Get instruments |
| GET | `/api/strategies/expiries?instrumentType=NIFTY` | Get expiries |
| GET | `/api/strategies/bot-status` | Get bot status |

**Execute Strategy:**
```json
{
  "strategyType": "ATM_STRADDLE",
  "instrumentType": "NIFTY",
  "expiry": "2025-12-26",
  "lots": 1,
  "orderType": "MARKET",
  "stopLossPoints": 20.0,
  "targetPoints": 30.0
}
```

---

### Monitoring

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/monitoring/connect` | Connect WebSocket |
| POST | `/api/monitoring/disconnect` | Disconnect WebSocket |
| GET | `/api/monitoring/status` | Get connection status |
| DELETE | `/api/monitoring/{executionId}` | Stop monitoring |

---

### Paper Trading

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/paper-trading/status` | Get paper trading status |
| POST | `/api/paper-trading/mode?paperTradingEnabled=true` | Toggle mode |
| POST | `/api/paper-trading/reset` | Reset account |

---

### Trading History

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/history/trades?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD` | Trade history |
| GET | `/api/history/trades/today` | Today's trades |
| GET | `/api/history/strategies` | Strategy executions |
| GET | `/api/history/daily-summary?startDate=&endDate=` | Daily P&L |

---

### GTT Orders

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/gtt` | List GTT orders |
| POST | `/api/gtt` | Place GTT order |
| GET | `/api/gtt/{triggerId}` | Get GTT by ID |
| PUT | `/api/gtt/{triggerId}` | Modify GTT |
| DELETE | `/api/gtt/{triggerId}` | Delete GTT |

---

### Account

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/account/margins/{segment}` | Get margins (equity/NFO) |

---

### Health

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/health` | Health check |

---

## 11. Extension Points

### Add New Strategy
1. Create class extending `BaseStrategy`
2. Implement `execute()` method
3. Register in `StrategyFactory`
4. Add to `StrategyType` enum

### Custom P&L Logic
1. Implement `PnLCalculationStrategy` interface
2. Register in factory

### New Exit Condition
Extend `PositionMonitor.checkAndTriggerCumulativeExitFast()`

### Additional Persistence
Create new entity and repository following existing patterns.

---

## Troubleshooting

| Issue | Cause | Solution |
|-------|-------|----------|
| 401 Unauthorized | Missing X-User-Id | Add header to all requests |
| No active session | Session expired | Re-authenticate |
| Strategy fails | Insufficient margin | Check `/api/account/margins/NFO` |
| WebSocket disconnect | Network issues | Auto-reconnect enabled |

### Debug Logging
```yaml
logging:
  level:
    com.tradingbot: DEBUG
    com.tradingbot.service.strategy: TRACE
```

---

## Known Limitations

1. **No Backtesting** - Historical replay not implemented
2. **Single Region** - No multi-region support
3. **Session Persistence** - Sessions not persisted across restarts
4. **Partial Fills** - Not tracked in FIXED P&L mode

---

## Resources

- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **Kite Connect Docs**: https://kite.trade/docs/connect/v3/
- **H2 Console**: http://localhost:8080/h2-console

