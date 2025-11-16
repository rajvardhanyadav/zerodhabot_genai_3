# Zerodha Trading Bot - Complete API & Functionality Documentation

**Version:** 2.3.2  
**Last Updated:** November 16, 2025  
**Base URL (Development):** `http://localhost:8080`  
**Base URL (Production):** `https://your-app.onrender.com`

---

## Table of Contents

1. [Overview](#overview)
2. [Technology Stack](#technology-stack)
3. [Quick Start Guide](#quick-start-guide)
4. [Authentication APIs](#authentication-apis)
5. [Order Management APIs](#order-management-apis)
6. [Portfolio APIs](#portfolio-apis)
7. [Market Data APIs](#market-data-apis)
8. [Account APIs](#account-apis)
9. [GTT (Good Till Triggered) APIs](#gtt-good-till-triggered-apis)
10. [Trading Strategies APIs](#trading-strategies-apis)
11. [Position Monitoring APIs](#position-monitoring-apis)
12. [Paper Trading](#paper-trading)
13. [Order Charges APIs](#order-charges-apis)
14. [Configuration](#configuration)
15. [Error Handling](#error-handling)
16. [Code Examples](#code-examples)
17. [Historical Replay APIs](#historical-replay-apis)
18. [Changelog](#changelog)

---

## Overview

A comprehensive Spring Boot backend application for automated trading using Zerodha's Kite Connect API. The application supports both live trading and paper trading modes, with sophisticated strategy execution and real-time position monitoring capabilities.

### Key Features

✅ **Authentication**: OAuth integration with Kite Connect  
✅ **Order Management**: Place, modify, cancel orders with multiple order types  
✅ **Portfolio Management**: Track positions, holdings, and P&L  
✅ **Market Data**: Real-time quotes, OHLC, LTP, and historical data  
✅ **GTT Orders**: Good Till Triggered order management  
✅ **Trading Strategies**: Pre-built strategies (ATM Straddle, ATM Strangle)  
✅ **Position Monitoring**: Real-time WebSocket-based monitoring with SL/Target  
✅ **Paper Trading**: Risk-free strategy testing with real market data  
✅ **Order Charges**: Calculate brokerage and charges before placing orders  
✅ **Individual Leg Exit**: Close individual legs of multi-leg strategies  
✅ **Delta-Based Strike Selection**: Accurate ATM strike selection using Black-Scholes  
✅ **Historical Replay (Backtest-like)**: Execute strategies over the most recent day's data with per-second replay derived from minute candles  
✅ **Multi-User Isolation**: All runtime state (sessions, WebSockets, orders, positions) is segregated per user via the `X-User-Id` header

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
  "data": [ /* Instrument[] */ ]
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
  "data": [ /* GTT[] */ ]
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
  "data": { /* GTT */ }
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
  "data": { /* GTT */ }
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
  "data": { /* GTT */ }
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
  "data": { /* GTT */ }
}
```

---

## Trading Strategies APIs

### Overview

Pre-built algorithmic trading strategies with automated execution, risk management, and real-time monitoring.

#### Available Strategies

1. **ATM Straddle** - Buy 1 ATM Call + Buy 1 ATM Put
2. **ATM Strangle** - Buy 1 OTM Call + Buy 1 OTM Put

---

### 1. Execute Strategy

Execute a trading strategy with configurable parameters.

**Endpoint:** `POST /api/strategies/execute`

**Request Body:**
```json
{
  "strategyType": "ATM_STRADDLE",
  "instrumentType": "NIFTY",
  "expiry": "WEEKLY",
  "lots": 1,
  "orderType": "MARKET",
  "strikeGap": 100,
  "stopLossPoints": 10.0,
  "targetPoints": 15.0,
  "autoSquareOff": false
}
```

**Request Fields:**

| Field | Type | Required | Description | Default |
|-------|------|----------|-------------|---------|
| strategyType | String | Yes | Strategy type | ATM_STRADDLE, ATM_STRANGLE |
| instrumentType | String | Yes | Index to trade | NIFTY, BANKNIFTY, FINNIFTY |
| expiry | String | Yes | Option expiry | WEEKLY, MONTHLY, or yyyy-MM-dd |
| lots | Integer | No | Number of lots | 1 |
| orderType | String | No | Order type | MARKET (default), LIMIT |
| strikeGap | Double | No | Strike gap for strangle | Auto-calculated |
| stopLossPoints | Double | No | Stop loss in points | 10.0 (from config) |
| targetPoints | Double | No | Target profit in points | 15.0 (from config) |
| autoSquareOff | Boolean | No | Auto exit at 3:15 PM | false |

**Response:**
```json
{
  "success": true,
  "message": "Strategy executed successfully",
  "data": {
    "executionId": "abc123-def456-ghi789",
    "status": "ACTIVE",
    "strategyType": "ATM_STRADDLE",
    "instrumentType": "NIFTY",
    "expiry": "2025-11-14",
    "atmStrike": 24000.0,
    "orders": [
      {
        "orderId": "221108000123456",
        "tradingSymbol": "NIFTY2511024000CE",
        "optionType": "CE",
        "strike": 24000.0,
        "quantity": 50,
        "entryPrice": 120.50,
        "status": "COMPLETE"
      },
      {
        "orderId": "221108000123457",
        "tradingSymbol": "NIFTY2511024000PE",
        "optionType": "PE",
        "strike": 24000.0,
        "quantity": 50,
        "entryPrice": 115.75,
        "status": "COMPLETE"
      }
    ],
    "totalPremium": 11812.50,
    "currentValue": 11812.50,
    "profitLoss": 0.0,
    "profitLossPercentage": 0.0,
    "stopLoss": 10.0,
    "target": 15.0,
    "timestamp": "2025-11-09 09:15:00"
  }
}
```

---

### 2. Get Active Strategies

Retrieve all currently active strategy executions.

**Endpoint:** `GET /api/strategies/active`

**Response:**
```json
{
  "success": true,
  "message": "Success",
  "data": [
    {
      "executionId": "abc123-def456-ghi789",
      "strategyType": "ATM_STRADDLE",
      "instrumentType": "NIFTY",
      "expiry": "2025-11-14",
      "status": "ACTIVE",
      "entryPrice": 11812.50,
      "currentPrice": 12500.00,
      "profitLoss": 687.50,
      "profitLossPercentage": 5.82,
      "stopLoss": 10.0,
      "target": 15.0,
      "timestamp": "2025-11-09 09:15:00"
    }
  ]
}
```

---

### 3. Get Strategy Details

Get details of a specific strategy execution.

**Endpoint:** `GET /api/strategies/{executionId}`

**Path Parameters:**
- `executionId` - Unique execution identifier

**Response:**
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "executionId": "abc123-def456-ghi789",
    "strategyType": "ATM_STRADDLE",
    "status": "ACTIVE",
    "orders": [
      {
        "orderId": "221108000123456",
        "tradingSymbol": "NIFTY2511024000CE",
        "optionType": "CE",
        "strike": 24000.0,
        "quantity": 50,
        "entryPrice": 120.50,
        "status": "COMPLETE"
      }
    ],
    "profitLoss": 687.50
  }
}
```

---

### 4. Stop Strategy

Stop a specific active strategy by closing all legs at market price.

**Endpoint:** `DELETE /api/strategies/stop/{executionId}`

**Path Parameters:**
- `executionId` - Unique execution identifier

**Response:**
```json
{
  "success": true,
  "message": "Strategy stopped successfully",
  "data": {
    "executionId": "abc123-def456-ghi789",
    "totalLegs": 2,
    "successCount": 2,
    "failureCount": 0,
    "status": "SUCCESS",
    "exitOrders": [
      {
        "tradingSymbol": "NIFTY2511024000CE",
        "optionType": "CE",
        "quantity": "50",
        "exitOrderId": "221108000789012",
        "status": "SUCCESS",
        "message": "Order placed successfully"
      },
      {
        "tradingSymbol": "NIFTY2511024000PE",
        "optionType": "PE",
        "quantity": "50",
        "exitOrderId": "221108000789013",
        "status": "SUCCESS",
        "message": "Order placed successfully"
      }
    ]
  }
}
```

---

### 5. Stop All Active Strategies

Stop all currently active strategies in one operation.

**Endpoint:** `DELETE /api/strategies/stop-all`

**Response:**
```json
{
  "success": true,
  "message": "All strategies stopped successfully",
  "data": {
    "totalStrategies": 2,
    "totalLegs": 4,
    "successCount": 4,
    "failureCount": 0,
    "strategies": [
      {
        "executionId": "abc123-def456-ghi789",
        "status": "SUCCESS",
        "legsExited": 2
      },
      {
        "executionId": "xyz789-uvw456-rst123",
        "status": "SUCCESS",
        "legsExited": 2
      }
    ]
  }
}
```

---

### 6. Get Available Strategy Types

Get list of all available strategy types.

**Endpoint:** `GET /api/strategies/types`

**Response:**
```json
{
  "success": true,
  "message": "Success",
  "data": [
    {
      "name": "ATM_STRADDLE",
      "description": "Buy ATM Call + Buy ATM Put (Non-directional strategy)",
      "implemented": true
    },
    {
      "name": "ATM_STRANGLE",
      "description": "Buy OTM Call + Buy OTM Put (Lower cost than straddle)",
      "implemented": true
    }
  ]
}
```

---

### 7. Get Available Instruments

Get list of tradeable instruments with details.

**Endpoint:** `GET /api/strategies/instruments`

**Response:**
```json
{
  "success": true,
  "message": "Success",
  "data": [
    {
      "code": "NIFTY",
      "name": "NIFTY 50",
      "lotSize": 50,
      "strikeInterval": 50.0
    },
    {
      "code": "BANKNIFTY",
      "name": "BANK NIFTY",
      "lotSize": 25,
      "strikeInterval": 100.0
    },
    {
      "code": "FINNIFTY",
      "name": "NIFTY FINANCIAL SERVICES",
      "lotSize": 40,
      "strikeInterval": 50.0
    }
  ]
}
```

---

## Position Monitoring APIs

- `GET /api/monitoring/status`
- `POST /api/monitoring/connect`
- `POST /api/monitoring/disconnect`
- `DELETE /api/monitoring/{executionId}`

All protected endpoints require `X-User-Id`.

---

## Paper Trading

### Overview

Paper trading allows testing strategies with **real-time market data** without risking actual money. All orders are simulated in-memory while using live prices from Kite API.

### Features

✅ Real-time market data from Kite API  
✅ Realistic order execution with slippage  
✅ Margin management (CNC, MIS, NRML)  
✅ Brokerage and tax calculations  
✅ Position & P&L tracking  
✅ Virtual account balance management  

---

### Configuration

Edit `application.yml`:

```yaml
trading:
  # Enable/disable paper trading
  paper-trading-enabled: true
  
  # Virtual account settings
  initial-balance: 1000000.0  # ₹10 Lakhs
  
  # Charges simulation
  apply-brokerage-charges: true
  brokerage-per-order: 20.0
  stt-percentage: 0.025
  transaction-charges: 0.00325
  gst-percentage: 18.0
  sebi-charges: 0.0001
  stamp-duty: 0.003
  
  # Execution simulation
  slippage-percentage: 0.05  # 0.05% on market orders
  enable-execution-delay: true
  execution-delay-ms: 500
  enable-order-rejection: false
  rejection-probability: 0.02
```

**Important:** Restart the application after changing this setting.

---

### API Endpoints

#### 1. Check Trading Mode

**Endpoint:** `GET /api/paper-trading/status`

**Response:**
```json
{
  "success": true,
  "message": "Trading mode retrieved",
  "data": {
    "paperTradingEnabled": true,
    "mode": "PAPER_TRADING",
    "description": "Simulated trading with virtual money",
    "balance": 1000000.0,
    "availableBalance": 988187.50,
    "usedMargin": 11812.50,
    "totalBrokerage": 40.0,
    "totalTax": 45.30,
    "totalTrades": 2
  }
}
```

---

#### 2. Get Paper Trading Account

**Endpoint:** `GET /api/paper-trading/account`

**Response:**
```json
{
  "success": true,
  "message": "Account details retrieved",
  "data": {
    "initialBalance": 1000000.0,
    "currentBalance": 1000687.50,
    "availableBalance": 988187.50,
    "usedMargin": 11812.50,
    "totalPnL": 687.50,
    "realizedPnL": 0.0,
    "unrealizedPnL": 687.50,
    "totalBrokerage": 40.0,
    "totalTax": 45.30,
    "totalTrades": 2,
    "winningTrades": 1,
    "losingTrades": 0,
    "winRate": 100.0
  }
}
```

---

#### 3. Reset Paper Trading Account

Reset account to initial state (useful for testing).

**Endpoint:** `POST /api/paper-trading/account/reset`

**Response:**
```json
{
  "success": true,
  "message": "Paper trading account reset successfully"
}
```

---

#### 4. Get Trading Statistics

**Endpoint:** `GET /api/paper-trading/statistics`

**Response:**
```json
{
  "success": true,
  "message": "Trading statistics",
  "data": { /* stats map */ }
}
```

---

#### 5. Get Paper Trading Info

Aggregated info including mode and, if enabled, account snapshot.

**Endpoint:** `GET /api/paper-trading/info`

**Response:**
```json
{
  "success": true,
  "message": "Success",
  "data": { /* info map */ }
}
```

---

## Order Charges APIs

### Calculate Order Charges (Planned)

Note: The backend currently exposes `GET /api/orders/charges` to fetch charges for executed orders today. A pre-trade calculation endpoint is planned and not yet implemented.

**Endpoint:** `POST /api/orders/charges` (not implemented)

**Request Body (example):**
```json
{
  "tradingSymbol": "NIFTY2511024000CE",
  "exchange": "NFO",
  "transactionType": "BUY",
  "quantity": 50,
  "price": 120.50,
  "product": "MIS"
}
```

**Response (example):**
```json
{
  "success": true,
  "message": "Order charges calculated",
  "data": {
    "orderValue": 6025.00,
    "brokerage": 20.00,
    "stt": 15.06,
    "transactionCharges": 1.96,
    "gst": 3.74,
    "sebiCharges": 0.01,
    "stampDuty": 0.18,
    "totalCharges": 40.95,
    "netAmount": 6065.95,
    "breakEvenPrice": 121.32
  }
}
```

---

## Configuration

### Application Configuration

Edit `src/main/resources/application.yml`:

```yaml
# Server Configuration
server:
  port: 8080

# Kite Connect Configuration
kite:
  api-key: ${KITE_API_KEY:your_api_key}
  api-secret: ${KITE_API_SECRET:your_api_secret}

# Trading Mode Configuration
trading:
  paper-trading-enabled: true  # false for live trading
  initial-balance: 1000000.0
  apply-brokerage-charges: true
  brokerage-per-order: 20.0
  stt-percentage: 0.025
  transaction-charges: 0.00325
  gst-percentage: 18.0
  sebi-charges: 0.0001
  stamp-duty: 0.003
  slippage-percentage: 0.05
  enable-execution-delay: true
  execution-delay-ms: 500
  enable-order-rejection: false
  rejection-probability: 0.02

# Strategy Configuration
strategy:
  default-stop-loss-points: 10.0
  default-target-points: 15.0
  auto-square-off-enabled: false
  auto-square-off-time: "15:15"

# Logging Configuration
logging:
  level:
    com.tradingbot: DEBUG
    com.zerodhatech.kiteconnect: INFO
```

---

### Environment Variables

You can override configuration using environment variables:

```cmd
set KITE_API_KEY=your_api_key
set KITE_API_SECRET=your_api_secret
set PAPER_TRADING_ENABLED=true
set INITIAL_BALANCE=1000000
set DEFAULT_STOP_LOSS=10
set DEFAULT_TARGET=15
```

---

## Error Handling

### Common Error Response Format

```json
{
  "success": false,
  "message": "Error description",
  "data": null
}
```

---

### Error Codes

| HTTP Code | Error Type | Description |
|-----------|------------|-------------|
| 400 | Bad Request | Invalid request parameters |
| 401 | Unauthorized | Invalid or missing access token |
| 403 | Forbidden | Insufficient permissions |
| 404 | Not Found | Resource not found |
| 409 | Conflict | Resource already exists |
| 500 | Internal Server Error | Server-side error |
| 503 | Service Unavailable | External service (Kite) unavailable |

---

### Common Errors

#### 1. Session Expired
```json
{
  "success": false,
  "message": "Session expired. Please login again.",
  "data": null
}
```
**Solution:** Call `/api/auth/login-url` and generate a new session.

---

#### 2. Insufficient Funds
```json
{
  "success": false,
  "message": "Insufficient funds to place order",
  "data": null
}
```
**Solution:** Check available margin using `/api/account/margins`.

---

#### 3. Invalid Order Parameters
```json
{
  "success": false,
  "message": "Price is required for LIMIT orders",
  "data": null
}
```
**Solution:** Provide all required fields based on order type.

---

#### 4. Strategy Already Active
```json
{
  "success": false,
  "message": "Strategy already active for this instrument",
  "data": null
}
```
**Solution:** Stop the existing strategy before starting a new one.

---

#### 5. Market Closed
```json
{
  "success": false,
  "message": "Market is currently closed",
  "data": null
}
```
**Solution:** Wait for market hours (9:15 AM - 3:30 PM IST).

---

## Code Examples

### JavaScript/TypeScript Examples

#### 1. Authentication Flow

```javascript
// Step 1: Get login URL
async function getLoginUrl() {
  const response = await fetch('http://localhost:8080/api/auth/login-url');
  const data = await response.json();
  
  if (data.success) {
    // Redirect user to login URL
    window.location.href = data.data;
  }
}

// Step 2: After redirect, extract request_token from URL
const urlParams = new URLSearchParams(window.location.search);
const requestToken = urlParams.get('request_token');

// Step 3: Generate session
async function generateSession(requestToken) {
  const response = await fetch('http://localhost:8080/api/auth/session', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': 'your-user-id'
    },
    body: JSON.stringify({ requestToken }),
  });
  
  const data = await response.json();
  
  if (data.success) {
    console.log('Logged in:', data.data);
    // Store access token if needed
    localStorage.setItem('accessToken', data.data.accessToken);
  }
}
```

---

#### 2. Place Order

```javascript
async function placeOrder() {
  const orderRequest = {
    tradingSymbol: 'INFY',
    exchange: 'NSE',
    transactionType: 'BUY',
    quantity: 1,
    product: 'CNC',
    orderType: 'MARKET',
  };
  
  const response = await fetch('http://localhost:8080/api/orders', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': 'your-user-id'
    },
    body: JSON.stringify(orderRequest),
  });
  
  const data = await response.json();
  
  if (data.success) {
    console.log('Order placed:', data.data.orderId);
  } else {
    console.error('Order failed:', data.message);
  }
}
```

---

#### 3. Execute Strategy

```javascript
async function executeATMStraddle() {
  const strategyRequest = {
    strategyType: 'ATM_STRADDLE',
    instrumentType: 'NIFTY',
    expiry: 'WEEKLY',
    lots: 1,
    stopLossPoints: 10.0,
    targetPoints: 15.0,
    autoSquareOff: false,
  };
  
  const response = await fetch('http://localhost:8080/api/strategies/execute', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': 'your-user-id'
    },
    body: JSON.stringify(strategyRequest),
  });
  
  const data = await response.json();
  
  if (data.success) {
    console.log('Strategy executed:', data.data);
    console.log('Execution ID:', data.data.executionId);
    console.log('Total Premium:', data.data.totalPremium);
  } else {
    console.error('Strategy failed:', data.message);
  }
}
```

---

#### 4. Monitor Active Strategies

```javascript
async function monitorStrategies() {
  const response = await fetch('http://localhost:8080/api/strategies/active');
  const data = await response.json();
  
  if (data.success) {
    data.data.forEach(strategy => {
      console.log(`Strategy: ${strategy.strategyType}`);
      console.log(`P&L: ₹${strategy.profitLoss} (${strategy.profitLossPercentage}%)`);
      console.log(`Status: ${strategy.status}`);
    });
  }
}

// Poll every 5 seconds
setInterval(monitorStrategies, 5000);
```

---

#### 5. Stop Strategy

```javascript
async function stopStrategy(executionId) {
  const response = await fetch(
    `http://localhost:8080/api/strategies/stop/${executionId}`,
    { method: 'DELETE' }
  );
  
  const data = await response.json();
  
  if (data.success) {
    console.log('Strategy stopped successfully');
    console.log('Exit orders:', data.data.exitOrders);
  } else {
    console.error('Failed to stop strategy:', data.message);
  }
}
```

---

#### 6. Get Positions

```javascript
async function getPositions() {
  const response = await fetch('http://localhost:8080/api/portfolio/positions');
  const data = await response.json();
  
  if (data.success) {
    const positions = data.data.net;
    
    positions.forEach(position => {
      console.log(`Symbol: ${position.tradingSymbol}`);
      console.log(`Quantity: ${position.quantity}`);
      console.log(`P&L: ₹${position.pnl}`);
    });
  }
}
```

---

#### 7. Calculate Order Charges

```javascript
async function calculateCharges() {
  const chargesRequest = {
    tradingSymbol: 'NIFTY2511024000CE',
    exchange: 'NFO',
    transactionType: 'BUY',
    quantity: 50,
    price: 120.50,
    product: 'MIS',
  };
  
  const response = await fetch('http://localhost:8080/api/orders/charges', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': 'your-user-id'
    },
    body: JSON.stringify(chargesRequest),
  });
  
  const data = await response.json();
  
  if (data.success) {
    console.log('Order Value:', data.data.orderValue);
    console.log('Total Charges:', data.data.totalCharges);
    console.log('Net Amount:', data.data.netAmount);
    console.log('Break Even:', data.data.breakEvenPrice);
  }
}
```

---

#### 8. Execute Strategy with Historical Replay (JavaScript)

```javascript
async function executeHistoricalReplay() {
  const payload = {
    strategyType: 'ATM_STRADDLE',
    instrumentType: 'NIFTY',
    expiry: 'WEEKLY',
    lots: 1,
    orderType: 'MARKET',
    stopLossPoints: 10.0,
    targetPoints: 15.0
  };

  const response = await fetch('http://localhost:8080/api/historical/execute', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });

  const data = await response.json();
  if (data.success) {
    console.log('Historical execution started:', data.data.executionId);
  } else {
    console.error('Historical execution failed:', data.message);
  }
}
```

---

## Historical Replay APIs

Backtest-like execution using the most recent trading day's historical data. Designed to reuse existing strategy and monitoring flows in paper mode while replaying prices per-second.

### 1. Execute Strategy with Historical Replay

**Endpoint:** `POST /api/historical/execute`

**Prerequisites:**
- Paper trading must be enabled (`trading.paper-trading-enabled: true`).
- A valid Kite session (access token) must be active.

**How it works:**
- The system executes the selected strategy in paper mode (same request shape as live execute).
- It then replays the most recent trading day's session (09:15–15:30 IST) for the option legs involved.
- Second-wise prices are synthesized by linearly interpolating minute candles from Kite Historical API.
- Monitoring and exits (SL/Target/leg thresholds) run as usual; replay runs asynchronously.

**Request Body (same as /api/strategies/execute):**
```json
{
  "strategyType": "ATM_STRADDLE",
  "instrumentType": "NIFTY",
  "expiry": "WEEKLY",
  "lots": 1,
  "orderType": "MARKET",
  "stopLossPoints": 10.0,
  "targetPoints": 15.0,
  "autoSquareOff": false
}
```

**Response:**
```json
{
  "success": true,
  "message": "Historical execution started",
  "data": {
    "executionId": "abc123-def456-ghi789",
    "status": "ACTIVE",
    "orders": [
      {
        "orderId": "221108000123456",
        "tradingSymbol": "NIFTY2511024000CE",
        "optionType": "CE",
        "strike": 24000.0,
        "quantity": 50,
        "entryPrice": 120.50,
        "status": "COMPLETE"
      },
      {
        "orderId": "221108000123457",
        "tradingSymbol": "NIFTY2511024000PE",
        "optionType": "PE",
        "strike": 24000.0,
        "quantity": 50,
        "entryPrice": 115.75,
        "status": "COMPLETE"
      }
    ],
    "totalPremium": 11812.50,
    "currentValue": 11812.50,
    "profitLoss": 0.0,
    "profitLossPercentage": 0.0
  }
}
```

**Notes & Limitations:**
- Replay uses minute candles for the latest trading day and derives second-wise prices by interpolation; this is not tick-by-tick accuracy.
- Replay is accelerated (milliseconds per simulated second) to complete in reasonable time.
- Endpoint is only available in paper mode; requests in live mode will be rejected.

---

## Changelog

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
