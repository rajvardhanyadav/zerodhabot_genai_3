# Zerodha Trading Bot - Complete API & Functionality Documentation

**Version:** 3.0.0  
**Last Updated:** November 23, 2025  
**Base URL (Development):** `http://localhost:8080`  
**Base URL (Production):** `https://your-app.onrender.com`

---

## Table of Contents

1. [Overview](#overview)
2. [Technology Stack](#technology-stack)
3. [Quick Start Guide](#quick-start-guide)
4. [Multi-User Model and Required Headers](#multi-user-model-and-required-headers)
5. [Authentication APIs](#authentication-apis)
6. [Order Management APIs](#order-management-apis)
7. [Portfolio APIs](#portfolio-apis)
8. [Market Data APIs](#market-data-apis)
9. [GTT (Good Till Triggered) APIs](#gtt-good-till-triggered-apis)
10. [Trading Strategies APIs](#trading-strategies-apis)
11. [Historical Replay APIs](#historical-replay-apis)
12. [Trading Mode Toggle API](#trading-mode-toggle-api)
13. [Bot Status API](#bot-status-api)
14. [Changelog](#changelog)

---

## Overview

A comprehensive Spring Boot backend application for automated trading using Zerodha's Kite Connect API. The application supports both live trading and paper trading modes, with sophisticated strategy execution, auto-reentry, and real-time position monitoring capabilities.

### Key Features

✅ **Authentication**: OAuth integration with Kite Connect  
✅ **Order Management**: Place, modify, cancel orders with multiple order types  
✅ **Portfolio Management**: Track positions, holdings, and P&L  
✅ **Market Data**: Real-time quotes, OHLC, LTP, and historical data  
✅ **GTT Orders**: Good Till Triggered order management  
✅ **Trading Strategies**: Pre-built strategies (ATM Straddle, Sell ATM Straddle)  
✅ **Auto-Reentry for ATM Straddle**: When SL/Target is hit, automatically schedule a new ATM straddle at the next 5-min candle (configurable)  
✅ **Position Monitoring**: Real-time WebSocket-based monitoring with SL/Target  
✅ **Paper Trading**: Risk-free strategy testing with real market data  
✅ **Order Charges**: Calculate brokerage and charges before placing orders  
✅ **Individual Leg Exit**: Close individual legs of multi-leg strategies  
✅ **Delta-Based Strike Selection**: Accurate ATM strike selection using Black-Scholes  
✅ **Historical Replay (Backtest-like)**: Execute strategies over the most recent day's data with per-second replay derived from minute candles, using the same monitoring/exit/auto-reentry logic  
✅ **Runtime Trading Mode Toggle**: Switch between paper and live trading at runtime via API  
✅ **Multi-User Isolation**: All runtime state (sessions, WebSockets, orders, positions) is segregated per user via the `X-User-Id` header  
✅ **Bot Status**: In-memory RUNNING/STOPPED status flipped on strategy execute/stop-all and queryable via API

---

## Technology Stack

- **Java 17**
- **Spring Boot 3.2.0**
- **Kite Connect Java SDK 3.5.1**
- **Maven (Wrapper included)**
- **Swagger/OpenAPI** for API documentation
- **Lombok** for reducing boilerplate code
- **WebSocket** for real-time market data

---

## Quick Start Guide

### Prerequisites

1. **Java 17** or higher
2. Zerodha Kite Connect API credentials (API Key and API Secret)
3. Active Zerodha trading account

Note: Maven Wrapper is included; a global Maven install is not required.

### Setup Instructions

#### 1. Get Kite Connect Credentials

1. Visit [Kite Connect](https://kite.trade/)
2. Create a new app to get your API Key and API Secret
3. Note down your credentials

#### 2. Configure Application

Edit `src/main/resources/application.yml`:

```yaml
kite:
  api-key: YOUR_API_KEY_HERE
  api-secret: YOUR_API_SECRET_HERE

trading:
  paper-trading-enabled: true  # false for live trading

strategy:
  default-stop-loss-points: 10.0
  default-target-points: 15.0
```

Or set environment variables (PowerShell):
```powershell
$env:KITE_API_KEY='your_api_key'
$env:KITE_API_SECRET='your_api_secret'
```

#### 3. Build and Run

Windows (PowerShell):
```powershell
# Build the project and run tests
./mvnw.cmd -DskipTests=false test

# Run the application
./mvnw.cmd spring-boot:run
```

macOS/Linux (bash):
```bash
# Build the project and run tests
./mvnw -DskipTests=false test

# Run the application
./mvnw spring-boot:run
```

The application will start on `http://localhost:8080`

#### 4. Access API Documentation

Open Swagger UI: `http://localhost:8080/swagger-ui.html` (OpenAPI JSON at `/api-docs`)

---

## Multi-User Model and Required Headers

This API is multi-tenant by design. Each request is processed under a specific user and all runtime state is isolated per user.

- Provide the following header on every protected API call:
  - `X-User-Id: <your-user-id>` (any opaque identifier: email, UUID, username)
- Session creation (`POST /api/auth/session`) is flexible:
  - If you supply `X-User-Id`, that value becomes the key for the session.
  - If you omit the header, the backend infers the id from Kite (`user.userId`) and returns it; use that id for subsequent protected calls.
- WebSocket connections are per user; after generating a session, call `POST /api/monitoring/connect` with the header to start streaming ticks.
- Paper trading accounts, orders, and positions are segregated by `X-User-Id`.

---

## Authentication APIs

### 1. Get Login URL

Get the Kite Connect login URL for OAuth authentication.

**Endpoint:** `GET /api/auth/login-url`

**Headers:**
```
Content-Type: application/json
```

**Response:**
```json
{
  "success": true,
  "message": "Login URL generated",
  "data": "https://kite.zerodha.com/connect/login?api_key=your_api_key&v=3"
}
```

**Usage Flow:**
1. Call this endpoint to get the login URL
2. Redirect user to this URL in browser
3. User logs in with Zerodha credentials
4. User is redirected back with `request_token` parameter

---

### 2. Generate Session

Generate access token using the request token received after login.

**Endpoint:** `POST /api/auth/session`

**Headers (optional):**
```
X-User-Id: <your-user-id>   # Optional; if absent userId from Kite is used
Content-Type: application/json
```

**Request Body:**
```json
{ "requestToken": "your_request_token_here" }
```

**Response (header provided):**
```json
{
  "success": true,
  "message": "Session generated successfully",
  "data": {
    "userId": "EXTERNAL-123",
    "accessToken": "your_access_token_here",
    "publicToken": "your_public_token_here"
  }
}
```

**Response (no header, inferred userId):**
```json
{
  "success": true,
  "message": "Session generated successfully",
  "data": {
    "userId": "AB1234",
    "accessToken": "your_access_token_here",
    "publicToken": "your_public_token_here"
  }
}
```

**Important:** Store the `accessToken` securely. The backend stores it per user for subsequent API calls.

---

### 3. Get User Profile

Get the profile details of the logged-in user.

**Endpoint:** `GET /api/auth/profile`

**Response:**
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "userId": "AB1234",
    "userName": "John Doe",
    "userShortname": "John",
    "email": "john@example.com",
    "userType": "individual",
    "broker": "ZERODHA",
    "exchanges": ["NSE", "BSE", "NFO", "MCX"],
    "products": ["CNC", "MIS", "NRML"],
    "orderTypes": ["MARKET", "LIMIT", "SL", "SL-M"]
  }
}
```

---

## Order Management APIs

### 1. Place Order

Place a new trading order.

**Endpoint:** `POST /api/orders`

**Request Body:**
```json
{
  "tradingSymbol": "INFY",
  "exchange": "NSE",
  "transactionType": "BUY",
  "quantity": 1,
  "product": "CNC",
  "orderType": "MARKET",
  "price": null,
  "triggerPrice": null,
  "validity": "DAY",
  "disclosedQuantity": 0
}
```

**Request Fields:**

| Field | Type | Required | Description | Values |
|-------|------|----------|-------------|--------|
| tradingSymbol | String | Yes | Trading symbol | INFY, TCS, RELIANCE, etc. |
| exchange | String | Yes | Exchange | NSE, BSE, NFO, MCX |
| transactionType | String | Yes | Buy or Sell | BUY, SELL |
| quantity | Integer | Yes | Number of shares | Any positive integer |
| product | String | Yes | Product type | CNC, MIS, NRML |
| orderType | String | Yes | Order type | MARKET, LIMIT, SL, SL-M |
| price | Double | No | Limit price | Required for LIMIT orders |
| triggerPrice | Double | No | Trigger price | Required for SL, SL-M |
| validity | String | No | Order validity | DAY, IOC (default: DAY) |
| disclosedQuantity | Integer | No | Disclosed quantity | Default: 0 |

**Response:**
```json
{
  "success": true,
  "message": "Order placed successfully",
  "data": {
    "orderId": "221108000123456",
    "status": "SUCCESS",
    "message": "Order placed successfully"
  }
}
```

---

### 2. Modify Order

Modify an existing pending order.

**Endpoint:** `PUT /api/orders/{orderId}`

**Path Parameters:**
- `orderId` - The order ID to modify

**Request Body:**
```json
{
  "quantity": 2,
  "price": 1500.0,
  "orderType": "LIMIT",
  "triggerPrice": null,
  "validity": "DAY"
}
```

**Response:**
```json
{
  "success": true,
  "message": "Order modified successfully",
  "data": {
    "orderId": "221108000123456",
    "status": "SUCCESS"
  }
}
```

---

### 3. Cancel Order

Cancel an existing pending order.

**Endpoint:** `DELETE /api/orders/{orderId}`

**Path Parameters:**
- `orderId` - The order ID to cancel

**Response:**
```json
{
  "success": true,
  "message": "Order cancelled successfully",
  "data": {
    "orderId": "221108000123456"
  }
}
```

---

### 4. Get All Orders

Retrieve all orders for the current trading day.

**Endpoint:** `GET /api/orders`

**Response:**
```json
{
  "success": true,
  "message": "Success",
  "data": [
    {
      "orderId": "221108000123456",
      "tradingSymbol": "INFY",
      "exchange": "NSE",
      "transactionType": "BUY",
      "quantity": 1,
      "price": 1450.50,
      "orderType": "MARKET",
      "product": "CNC",
      "status": "COMPLETE",
      "orderTimestamp": "2025-11-09 09:15:30"
    }
  ]
}
```

---

### 5. Get Order History

Get the history of an order with all status updates.

**Endpoint:** `GET /api/orders/{orderId}/history`

**Response:**
```json
{
  "success": true,
  "message": "Success",
  "data": [
    {
      "orderId": "221108000123456",
      "status": "COMPLETE",
      "statusMessage": "Order executed",
      "timestamp": "2025-11-09 09:15:35"
    }
  ]
}
```

---

### 6. Get Trades

Get all executed trades for the current day.

**Endpoint:** `GET /api/orders/trades`

**Response:**
```json
{
  "success": true,
  "message": "Success",
  "data": [
    {
      "tradeId": "12345678",
      "orderId": "221108000123456",
      "tradingSymbol": "INFY",
      "exchange": "NSE",
      "transactionType": "BUY",
      "quantity": 1,
      "price": 1450.50,
      "tradeTimestamp": "2025-11-09 09:15:35"
    }
  ]
}
```

---

### 7. Get Order Charges (Executed Orders Today)

Fetch detailed charges for all executed orders placed today from Kite API.

**Endpoint:** `GET /api/orders/charges`

**Response:**
```json
{
  "success": true,
  "message": "Order charges fetched successfully",
  "data": [
    {
      "orderId": "221108000123456",
      "charges": {
        "brokerage": 20.0,
        "stt": 15.06,
        "transactionCharges": 1.96,
        "gst": 3.74,
        "sebiCharges": 0.01,
        "stampDuty": 0.18,
        "totalCharges": 40.95
      }
    }
  ]
}
```

---

## Portfolio APIs

### 1. Get Positions

Get all open positions for the current day.

**Endpoint:** `GET /api/portfolio/positions`

**Response:**
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "net": [
      {
        "tradingSymbol": "INFY",
        "exchange": "NSE",
        "product": "CNC",
        "quantity": 1,
        "averagePrice": 1450.50,
        "lastPrice": 1460.00,
        "pnl": 9.50,
        "value": 1450.50
      }
    ],
    "day": []
  }
}
```

---

### 2. Get Holdings

Get all long-term holdings (delivery positions).

**Endpoint:** `GET /api/portfolio/holdings`

**Response:**
```json
{
  "success": true,
  "message": "Success",
  "data": [
    {
      "tradingSymbol": "INFY",
      "exchange": "NSE",
      "quantity": 10,
      "averagePrice": 1400.00,
      "lastPrice": 1460.00,
      "pnl": 600.00,
      "value": 14600.00
    }
  ]
}
```

---

## Market Data APIs

### 1. Get Quote

Get market quote for one or more instruments.

**Endpoint:** `GET /api/market/quote`

**Query Parameters:**
- `symbols` - Comma-separated list of symbols (e.g., NSE:INFY,BSE:SENSEX)

**Response:**
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "NSE:INFY": {
      "instrumentToken": 408065,
      "lastPrice": 1460.00,
      "lastQuantity": 1,
      "averagePrice": 1455.00,
      "volume": 1000000,
      "buyQuantity": 500,
      "sellQuantity": 450,
      "ohlc": {
        "open": 1450.00,
        "high": 1465.00,
        "low": 1445.00,
        "close": 1455.00
      }
    }
  }
}
```

---

### 2. Get OHLC

Get OHLC (Open, High, Low, Close) data for instruments.

**Endpoint:** `GET /api/market/ohlc`

**Query Parameters:**
- `symbols` - Comma-separated list of symbols

**Response:**
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "NSE:INFY": {
      "open": 1450.00,
      "high": 1465.00,
      "low": 1445.00,
      "close": 1455.00,
      "lastPrice": 1460.00
    }
  }
}
```

---

### 3. Get LTP (Last Traded Price)

Get the last traded price for instruments.

**Endpoint:** `GET /api/market/ltp`

**Query Parameters:**
- `symbols` - Comma-separated list of symbols

**Response:**
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "NSE:INFY": {
      "instrumentToken": 408065,
      "lastPrice": 1460.00
    }
  }
}
```

---

### 4. Get Historical Data

Get historical candle data for an instrument.

**Endpoint:** `GET /api/market/historical`

**Query Parameters:**
- `instrumentToken` - Instrument token (e.g., 408065)
- `from` - From date (yyyy-MM-dd format)
- `to` - To date (yyyy-MM-dd format)
- `interval` - Candle interval (minute, day, 3minute, 5minute, 10minute, 15minute, 30minute, 60minute)

**Response:**
```json
{
  "success": true,
  "message": "Success",
  "data": [
    {
      "timestamp": "2025-11-09 09:15:00",
      "open": 1450.00,
      "high": 1455.00,
      "low": 1448.00,
      "close": 1453.00,
      "volume": 10000
    }
  ]
}
```

---

### 5. Get Instruments

Get list of all tradeable instruments.

**Endpoint:** `GET /api/market/instruments`

**Response:**
```json
{
  "success": true,
  "message": "Success",
  "data": [
    {
      "instrumentToken": 408065,
      "exchangeToken": 1594,
      "tradingSymbol": "INFY",
      "name": "INFOSYS LTD",
      "lastPrice": 1460.00,
      "expiry": null,
      "strike": 0.0,
      "tickSize": 0.05,
      "lotSize": 1,
      "instrumentType": "EQ",
      "segment": "NSE",
      "exchange": "NSE"
    }
  ]
}
```

---

### 6. Get Instruments by Exchange

Get instruments for a specific exchange.

**Endpoint:** `GET /api/market/instruments/{exchange}`

**Path Parameters:**
- `exchange` - NSE, BSE, NFO, MCX

**Response:**
```json
{
  "success": true,
  "message": "Success",
  "data": []
}
```

---

## GTT (Good Till Triggered) APIs

### 1. Get All GTT Orders

Get all active GTT orders.

**Endpoint:** `GET /api/gtt`

**Response:**
```json
{
  "success": true,
  "message": "Success",
  "data": []
}
```

---

### 2. Place GTT Order

Create a new GTT order.

**Endpoint:** `POST /api/gtt`

**Request Body:** `GTTParams`

**Response:**
```json
{
  "success": true,
  "message": "GTT order placed successfully",
  "data": {}
}
```

---

### 3. Get GTT by ID

**Endpoint:** `GET /api/gtt/{triggerId}`

**Response:**
```json
{
  "success": true,
  "message": "Success",
  "data": {}
}
```

---

### 4. Modify GTT

**Endpoint:** `PUT /api/gtt/{triggerId}`

**Request Body:** `GTTParams`

**Response:**
```json
{
  "success": true,
  "message": "GTT order modified successfully",
  "data": {}
}
```

---

### 5. Delete GTT

**Endpoint:** `DELETE /api/gtt/{triggerId}`

**Response:**
```json
{
  "success": true,
  "message": "GTT order cancelled successfully",
  "data": {}
}
```

---

## Trading Strategies APIs

### Strategy Types

Currently implemented strategies:

- `ATM_STRADDLE` (supported) - Buy 1 ATM Call + Buy 1 ATM Put
- `SELL_ATM_STRADDLE` (supported) - Sell 1 ATM Call + Sell 1 ATM Put

The `/api/strategies/types` endpoint indicates implementation status.

### 1. Execute Strategy

Execute a trading strategy such as ATM Straddle or Sell ATM Straddle.

**Endpoint:** `POST /api/strategies/execute`

**Headers:**
```
X-User-Id: <your-user-id>
Content-Type: application/json
```

**Request Body:**
```json
{
  "strategyType": "ATM_STRADDLE",
  "instrumentType": "NIFTY",
  "expiry": "2025-11-27",
  "lots": 1,
  "orderType": "MARKET",
  "autoSquareOff": false,
  "stopLossPoints": 20.0,
  "targetPoints": 30.0
}
```

> NOTE: If `strategyType` is omitted or null, the backend defaults to `ATM_STRADDLE` for `/api/strategies/execute`.

**Response:**
```json
{
  "success": true,
  "message": "Strategy executed successfully",
  "data": {
    "executionId": "a1b2c3d4-...",
    "status": "ACTIVE",
    "message": "[PAPER] Strategy started with SL=20.0pts, Target=30.0pts",
    "orders": [
      {
        "orderId": "221108000123456",
        "tradingSymbol": "NIFTY25NOV18000CE",
        "optionType": "CE",
        "strike": 18000.0,
        "quantity": 50,
        "price": 120.5,
        "status": "COMPLETE"
      },
      {
        "orderId": "221108000123457",
        "tradingSymbol": "NIFTY25NOV18000PE",
        "optionType": "PE",
        "strike": 18000.0,
        "quantity": 50,
        "price": 115.2,
        "status": "COMPLETE"
      }
    ],
    "totalPremium": 11785.0,
    "currentValue": 11785.0,
    "profitLoss": 0.0,
    "profitLossPercentage": 0.0
  }
}
```

> Bot Status Semantics: The bot status flips to RUNNING only after a successful `POST /api/strategies/execute` response, and flips back to STOPPED only after a successful `DELETE /api/strategies/stop-all`. Stopping an individual execution does not change the bot status.

### ATM Straddle Auto-Reentry Behavior

For `ATM_STRADDLE`, when enabled in config (`strategy.auto-restart-*`), the backend can automatically restart the strategy when SL/Target is hit:

1. The current ATM straddle is monitored in real-time.
2. When either stop-loss or target is triggered, both legs are exited and the strategy execution is marked `COMPLETED` with reason `STOPLOSS_HIT` or `TARGET_HIT`.
3. If auto-restart is enabled and limits are not exceeded, a new ATM straddle is scheduled at the start of the **next 5-minute candle** using the current underlying price to compute the new ATM.
4. Each auto-reentry creates a new `StrategyExecution` linked to the previous one, allowing the UI to visualize chains.

**Config flags controlling auto-reentry** (in `application.yml`):

```yaml
strategy:
  auto-restart-enabled: true           # master switch
  auto-restart-paper-enabled: true     # enable for paper mode
  auto-restart-live-enabled: false     # enable for live mode (use with care)
  max-auto-restarts: 0                 # 0 or negative -> unlimited, positive -> max count per chain
```

Auto-reentry applies to both live and paper strategies, and the same mechanism is used when running via historical replay (see [Historical Replay APIs](#historical-replay-apis)).

---

## Historical Replay APIs

Historical replay lets you execute a strategy in **paper mode** and then replay the most recent trading day tick-by-tick (derived from minute candles) to simulate intraday behavior.

### 1. Execute Strategy with Historical Replay

**Endpoint:** `POST /api/historical/execute`

**Headers:**
```
X-User-Id: <your-user-id>
Content-Type: application/json
```

**Request Body:** (same as `/api/strategies/execute`)
```json
{
  "strategyType": "ATM_STRADDLE",
  "instrumentType": "BANKNIFTY",
  "expiry": "2025-11-27",
  "lots": 1,
  "orderType": "MARKET",
  "stopLossPoints": 40.0,
  "targetPoints": 60.0
}
```

**Behavior:**

1. The strategy is executed in **paper trading mode** (live subscriptions temporarily disabled).  
2. A `PositionMonitor` is created for the execution.  
3. Historical second-wise prices for all legs are fetched for the most recent trading day.  
4. A background task replays ticks into the monitor, applying the same SL/Target and exit logic as live mode.  
5. When SL/Target is hit during replay, the execution is marked `COMPLETED` with the structured completion reason.  
6. Once replay finishes, the system uses the same auto-reentry mechanism as live strategies to optionally schedule a **new ATM straddle** at the next 5-minute candle (if auto-restart is enabled in config).

This allows you to test how auto-reentry chains would behave on recent historical data without risking real capital.

---


## Trading Mode Toggle API

The backend can run in **paper trading mode** or **live trading mode**. While `trading.paper-trading-enabled` in configuration sets the default at startup, you can also switch modes at runtime via an API.

### 1. Get Current Mode

**Endpoint:** `GET /api/paper-trading/status`

**Response:**
```json
{
  "success": true,
  "data": {
    "paperTradingEnabled": true,
    "mode": "PAPER_TRADING",
    "description": "Simulated trading with virtual money"
  }
}
```

### 2. Toggle Paper/Live Mode

**Endpoint:** `POST /api/paper-trading/mode`

**Headers:**
```
X-User-Id: <admin-or-user-id>
Content-Type: application/json
```

**Query Parameters:**
- `paperTradingEnabled` (boolean): `true` for paper mode, `false` for live mode

**Example:**

```http
POST /api/paper-trading/mode?paperTradingEnabled=false
X-User-Id: admin-user
```

**Response:**
```json
{
  "success": true,
  "message": "Trading mode updated successfully",
  "data": {
    "paperTradingEnabled": false,
    "mode": "LIVE_TRADING",
    "description": "Real trading with actual money"
  }
}
```

If mode is already the requested value, the endpoint returns a success response indicating that no change was made:

```json
{
  "success": true,
  "data": {
    "paperTradingEnabled": true,
    "mode": "PAPER_TRADING",
    "message": "Trading mode is already set to PAPER_TRADING"
  }
}
```

### Audit Logging

Every time the trading mode is toggled via this endpoint, the backend writes a structured audit log entry including:

- Timestamp (UTC `Instant`)
- User id (from `X-User-Id` / `CurrentUserContext` if available)
- Previous mode
- New mode

Example log line:

```text
[AUDIT] Trading mode toggled at 2025-11-16T17:25:42.123Z by user=admin-user from PAPER_TRADING to LIVE_TRADING
```

> **Security Recommendation:** Restrict access to `/api/paper-trading/mode` to administrative users at the API gateway / auth layer, since it determines whether orders are simulated or sent to the live exchange.

---

## Bot Status API

Query the current bot status.

### 1. Get Bot Status

**Endpoint:** `GET /api/strategies/bot-status`

**Response:**
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "status": "RUNNING",
    "lastUpdated": "2025-11-18T09:30:00Z"
  }
}
```

**Notes:**
- Status is maintained in-memory and is not persisted across restarts.
- Status changes only on `/api/strategies/execute` (RUNNING) and `/api/strategies/stop-all` (STOPPED).

---

## Changelog

### v3.0.0 (November 23, 2025) ⭐ NEW
- **Added comprehensive Backtesting Framework:**
  - New endpoints: `POST /api/backtest/execute`, `POST /api/backtest/batch`, `GET /api/backtest/{backtestId}`, `GET /api/backtest/health`
  - Single backtest execution on latest previous trading day (automatic) or custom date
  - Batch backtesting for parameter optimization and strategy comparison
  - Detailed performance metrics:
    - Total premium paid/received, gross/net P&L
    - Return percentage, ROI
    - Max profit, max drawdown during trade
    - Holding duration, number of trades
    - All trading charges (brokerage, STT, transaction charges, GST, SEBI, stamp duty)
  - Trade event timeline (entry, price updates, exit)
  - Configurable replay speed (0=fastest, 1=real-time)
  - Optional detailed tick-by-tick logging

### v2.5.0 (November 18, 2025)
- Added Bot Status feature:
  - In-memory status with timestamp, flipped to RUNNING on `/api/strategies/execute` and to STOPPED on `/api/strategies/stop-all`.
  - New endpoint: `GET /api/strategies/bot-status` returning `{ status, lastUpdated }`.
- Documentation updated (README, Architecture, Complete API).

### v2.4.0 (November 16, 2025)

- Added auto-reentry mechanism for ATM Straddle strategies:
  - When SL/Target is hit, exit both legs, mark execution `COMPLETED`, and optionally schedule a new ATM straddle at the next 5-minute candle.
  - Configurable via `strategy.auto-restart-*` flags and `max-auto-restarts`.
- Extended historical replay to use the same auto-reentry behavior after simulated runs.
- Added runtime trading mode toggle API: `POST /api/paper-trading/mode`.
- Added audit logging for trading mode changes including user id and timestamp.

- 2.3.2 (2025-11-16)
  - Updated docs to match implemented endpoints: `GET /api/orders/charges`, `GET /api/account/margins/{segment}`.
  - Added Swagger UI and OpenAPI paths.
  - Added Historical Replay speed configuration.
  - Clarified Paper Trading endpoints and routing behavior.

- 2.3.1 (2025-11-15)
  - Made `X-User-Id` optional for `/api/auth/session`; userId inferred from Kite when header omitted.
  - Updated documentation across README and Architecture to reflect flexible session creation.
  - Clarified response examples for session generation (header vs inferred).

- 2.3.0 (2025-11-15)
  - Multi-user model clarified and enforced: `X-User-Id` header documented and required on protected endpoints.
  - WebSocketService refactored to per-user connections and instrument maps.
  - Re-enabled `POST /api/monitoring/connect`; updated response examples and error cases.
  - Simplified monitoring status payload to `{ connected, activeMonitors }` to match implementation.
  - Introduced Maven Wrapper; updated build/run instructions to use `mvnw`/`mvnw.cmd`.

- 2.2.0
  - Previous features and docs improvements.

---

## License

This project is for educational and testing purposes only. Use at your own risk.

---

**End of Documentation**
