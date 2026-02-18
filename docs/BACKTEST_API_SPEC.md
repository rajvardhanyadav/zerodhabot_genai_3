# Backtesting API Documentation

## Overview
The Backtesting API allows users to test trading strategies against historical market data. It's completely isolated from live trading and provides detailed performance metrics.

**Base URL:** `/api/backtest`

---

## API Endpoints

### 1. Run Single-Day Backtest
**POST** `/api/backtest/run`

Execute a strategy backtest for a specific day using historical data.

#### Request Body
```json
{
  "backtestDate": "2025-01-15",
  "strategyType": "SELL_ATM_STRADDLE",
  "instrumentType": "NIFTY",
  "expiryDate": "2025-01-16",
  "lots": 1,
  "slTargetMode": "points",
  "stopLossPoints": 30,
  "targetPoints": 20,
  "candleInterval": "minute",
  "fastForwardEnabled": true
}
```

#### Request Fields
| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `backtestDate` | `string (yyyy-MM-dd)` | ✅ Yes | - | The specific trading day to backtest |
| `strategyType` | `enum` | ✅ Yes | - | Strategy type: `ATM_STRADDLE`, `SELL_ATM_STRADDLE` |
| `instrumentType` | `string` | ✅ Yes | - | Underlying index: `NIFTY`, `BANKNIFTY` |
| `expiryDate` | `string (yyyy-MM-dd)` | ✅ Yes | - | Expiry date for options contracts (e.g., `2025-01-16`) |
| `lots` | `integer` | No | `1` | Number of lots to simulate |
| `slTargetMode` | `string` | No | `"points"` | Exit mode: `"points"` or `"percentage"` |
| `stopLossPoints` | `number` | No | - | Stop loss in points (when mode = "points") |
| `targetPoints` | `number` | No | - | Target in points (when mode = "points") |
| `targetDecayPct` | `number` | No | - | Target decay % (when mode = "percentage") |
| `stopLossExpansionPct` | `number` | No | - | SL expansion % (when mode = "percentage") |
| `startTime` | `string (HH:mm)` | No | `"09:15"` | Backtest start time |
| `endTime` | `string (HH:mm)` | No | `"15:30"` | Backtest end time |
| `candleInterval` | `string` | No | `"minute"` | Candle interval: `minute`, `5minute`, `15minute` (uses minimum for highest precision) |
| `fastForwardEnabled` | `boolean` | No | `true` | Fast-forward to next 5-min boundary on restart |

#### Response
```json
{
  "success": true,
  "message": "Backtest completed successfully",
  "data": {
    "backtestId": "550e8400-e29b-41d4-a716-446655440000",
    "backtestDate": "2025-01-15",
    "strategyType": "SELL_ATM_STRADDLE",
    "instrumentType": "NIFTY",
    "status": "COMPLETED",
    "trades": [
      {
        "tradeNumber": 1,
        "tradingSymbol": "NIFTY25JAN24500CE",
        "optionType": "CE",
        "strikePrice": 24500,
        "entryTime": "2025-01-15T09:20:00",
        "entryPrice": 125.50,
        "exitTime": "2025-01-15T11:35:00",
        "exitPrice": 105.25,
        "quantity": 25,
        "transactionType": "SELL",
        "pnlPoints": 20.25,
        "pnlAmount": 506.25,
        "exitReason": "TARGET_HIT",
        "wasRestarted": false
      }
    ],
    "totalPnLPoints": 45.50,
    "totalPnLAmount": 1137.50,
    "totalTrades": 4,
    "winningTrades": 3,
    "losingTrades": 1,
    "winRate": 75.00,
    "maxDrawdownPct": 12.50,
    "maxProfitPct": 8.25,
    "avgWinAmount": 450.00,
    "avgLossAmount": 212.50,
    "profitFactor": 2.12,
    "restartCount": 1,
    "executionStartTime": "2025-01-15T10:30:00",
    "executionEndTime": "2025-01-15T10:30:02",
    "executionDurationMs": 2345
  }
}
```

---

### 2. Run Batch Backtest (Multiple Days)
**POST** `/api/backtest/batch?fromDate=2025-01-01&toDate=2025-01-31`

Execute backtests for each trading day in a date range.

#### Query Parameters
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `fromDate` | `string (yyyy-MM-dd)` | ✅ Yes | Start date (inclusive) |
| `toDate` | `string (yyyy-MM-dd)` | ✅ Yes | End date (inclusive) |

#### Request Body
Same as single-day backtest (without `backtestDate` - uses query params instead).

#### Response
```json
{
  "success": true,
  "message": "Batch backtest completed: 22 days processed",
  "data": [
    { /* BacktestResult for day 1 */ },
    { /* BacktestResult for day 2 */ }
  ]
}
```

---

### 3. Run Async Backtest
**POST** `/api/backtest/run-async`

Start a backtest in the background (for long-running operations).

#### Request Body
Same as single-day backtest.

#### Response
```json
{
  "success": true,
  "message": "Backtest started. Poll /api/backtest/result/{id} for results.",
  "data": "pending"
}
```

---

### 4. Get Backtest Result by ID
**GET** `/api/backtest/result/{backtestId}`

Retrieve a completed backtest result.

#### Path Parameters
| Parameter | Type | Description |
|-----------|------|-------------|
| `backtestId` | `string (UUID)` | Backtest execution ID |

#### Response
```json
{
  "success": true,
  "data": { /* BacktestResult object */ }
}
```

---

### 5. Get All Cached Results
**GET** `/api/backtest/results`

Retrieve all backtest results currently in cache.

#### Response
```json
{
  "success": true,
  "data": [
    { /* BacktestResult 1 */ },
    { /* BacktestResult 2 */ }
  ]
}
```

---

### 6. Get Supported Strategies
**GET** `/api/backtest/strategies`

List all strategy types available for backtesting.

#### Response
```json
{
  "success": true,
  "data": [
    {
      "name": "ATM_STRADDLE",
      "description": "Buy ATM Call and Put options (long straddle)",
      "backtestSupported": true
    },
    {
      "name": "SELL_ATM_STRADDLE",
      "description": "Sell ATM Call and Put options (short straddle)",
      "backtestSupported": true
    }
  ]
}
```

---

### 7. Clear Cache
**DELETE** `/api/backtest/cache`

Remove all cached backtest results.

#### Response
```json
{
  "success": true,
  "message": "Cache cleared successfully",
  "data": null
}
```

---

## Data Types

### BacktestStatus (Enum)
| Value | Description |
|-------|-------------|
| `COMPLETED` | Backtest finished successfully |
| `FAILED` | Backtest encountered an error |
| `PARTIAL` | Backtest partially completed |
| `RUNNING` | Backtest is still executing |

### Exit Reasons
| Value | Description |
|-------|-------------|
| `TARGET_HIT` | Profit target reached |
| `STOPLOSS_HIT` | Stop loss triggered |
| `SQUARE_OFF` | Market close auto-exit |
| `RESTART` | Strategy restart triggered |

### SL/Target Modes
| Mode | Description |
|------|-------------|
| `points` | Fixed point-based exits using `stopLossPoints` and `targetPoints` |
| `percentage` | Premium-based exits using `targetDecayPct` and `stopLossExpansionPct` |

---

## UI Component Suggestions

### 1. Backtest Configuration Form
**Fields needed:**
- Date picker for `backtestDate`
- Dropdown for `strategyType` (fetch from `/strategies`)
- Dropdown for `instrumentType` (NIFTY, BANKNIFTY)
- Dropdown for `expiry` (WEEKLY, MONTHLY, or date picker)
- Number input for `lots`
- Toggle/Radio for `slTargetMode` (points vs percentage)
- Conditional fields based on mode:
  - Points mode: `stopLossPoints`, `targetPoints`
  - Percentage mode: `targetDecayPct`, `stopLossExpansionPct`
- Advanced options (collapsible):
  - `candleInterval` dropdown
  - `fastForwardEnabled` toggle

### 2. Results Dashboard
**Summary Cards:**
- Total P&L (₹ and points)
- Win Rate (%)
- Profit Factor
- Max Drawdown (%)
- Total Trades
- Restart Count

**Charts:**
- Equity curve (cumulative P&L over time)
- Trade distribution (pie chart: wins vs losses)
- P&L bar chart by trade

### 3. Trade History Table
**Columns:**
- Trade # | Symbol | Type | Strike | Entry Time | Entry Price | Exit Time | Exit Price | P&L (₹) | P&L (pts) | Exit Reason

### 4. Batch Results View
**Table with:**
- Date | Status | Total P&L | Win Rate | Trades | Max Drawdown
- Click row to expand and see individual day's trades

### 5. Error States
- Handle `FAILED` status with error message display
- Loading spinner during execution
- Validation errors for form inputs

---

## Example UI Workflow

1. **User selects parameters** in configuration form
2. **Click "Run Backtest"** → calls `POST /api/backtest/run`
3. **Show loading spinner** while waiting
4. **On success:** Display results dashboard + trade table
5. **Option to run batch:** Shows date range picker, calls `/batch`
6. **Results persist in list:** Can click any previous result to view details

---

## Color Coding Suggestions
- **Profit:** Green (`#22c55e`)
- **Loss:** Red (`#ef4444`)
- **Neutral/Info:** Blue (`#3b82f6`)
- **Warning:** Amber (`#f59e0b`)
- **Status badges:**
  - COMPLETED: Green
  - FAILED: Red
  - RUNNING: Blue/pulsing
  - PARTIAL: Amber

---

## Frontend Copilot Prompt

Use this prompt to generate the Backtest UI:

```
Create a React/Next.js Backtesting UI for a trading application with the following:

1. **Backtest Configuration Page** (`/backtest`)
   - Form with fields: date picker, strategy dropdown, instrument dropdown (NIFTY/BANKNIFTY), expiry, lots, SL/Target mode toggle
   - When mode is "points": show stopLossPoints and targetPoints inputs
   - When mode is "percentage": show targetDecayPct and stopLossExpansionPct inputs
   - "Run Backtest" button that POSTs to `/api/backtest/run`
   - "Run Batch" option with date range pickers

2. **Results Dashboard**
   - Summary cards: Total P&L, Win Rate, Profit Factor, Max Drawdown, Total Trades
   - Equity curve chart showing cumulative P&L over trades
   - Pie chart showing win/loss distribution

3. **Trade History Table**
   - Columns: Trade#, Symbol, Option Type, Strike, Entry Time, Entry Price, Exit Time, Exit Price, P&L, Exit Reason
   - Color code: green for profit, red for loss
   - Sortable and filterable

4. **Batch Results View**
   - Table showing all days with Date, Status, P&L, Win Rate
   - Click to expand and see individual trades for that day

5. **State Management**
   - Loading states during API calls
   - Error handling with toast notifications
   - Store previous results in local state/cache

Use Tailwind CSS, shadcn/ui components, and react-query for API calls.
The backend API is documented above with all request/response formats.
```

