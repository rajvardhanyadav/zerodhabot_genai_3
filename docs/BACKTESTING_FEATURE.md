# Backtesting Feature Documentation

## Overview

The backtesting feature allows you to test trading strategies using historical data from past trading days. This helps evaluate strategy performance without risking real money.

## Key Features

### 1. Single Backtest Execution
- Test a single strategy on historical data
- Runs on the latest previous trading day by default
- Can specify a custom backtest date
- Provides detailed performance metrics and trade events
- Requires paper trading mode to be enabled

### 2. Batch Backtesting
- Run multiple backtests in parallel or sequentially
- Test different parameters or strategies simultaneously
- Aggregate statistics across all backtests
- Win rate, average returns, best/worst performance analysis

### 3. Detailed Performance Metrics
- Total premium paid/received
- Gross and net P&L (including charges)
- Return percentage and ROI
- Max drawdown and max profit during trade
- Holding duration
- Number of trades executed

### 4. Trade Event Tracking
- Entry events with initial prices
- Periodic price updates during the trade
- Exit events with completion reason
- Unrealized P&L at each event

## API Endpoints

### Execute Single Backtest

**Endpoint:** `POST /api/backtest/execute`

**Request Body:**
```json
{
  "strategyType": "ATM_STRADDLE",
  "instrumentType": "NIFTY",
  "expiry": "2025-11-28",
  "lots": 1,
  "orderType": "MARKET",
  "stopLossPoints": 10.0,
  "targetPoints": 15.0,
  "backtestDate": null,
  "replaySpeedMultiplier": 0,
  "includeDetailedLogs": false
}
```

**Parameters:**
- `strategyType` (required): Strategy to backtest (ATM_STRADDLE, ATM_STRANGLE, etc.)
- `instrumentType` (required): Instrument to trade (NIFTY, BANKNIFTY, FINNIFTY)
- `expiry`: Option expiry date (format: yyyy-MM-dd), defaults to expiry for backtest date
- `lots`: Number of lots (default: 1)
- `orderType`: MARKET or LIMIT (default: MARKET)
- `stopLossPoints`: Stop loss in points (optional)
- `targetPoints`: Target profit in points (optional)
- `strikeGap`: Strike gap for strangle strategy
- `backtestDate`: Date to run backtest on (default: latest previous trading day)
- `replaySpeedMultiplier`: Speed of replay (0 = fastest, 1 = real-time, default: 0)
- `includeDetailedLogs`: Include tick-by-tick price updates (default: false)

**Response:**
```json
{
  "status": "success",
  "message": "Backtest completed successfully",
  "data": {
    "backtestId": "uuid",
    "strategyType": "ATM_STRADDLE",
    "instrumentType": "NIFTY",
    "backtestDate": "2025-11-22",
    "startTime": "2025-11-23T10:30:00",
    "endTime": "2025-11-23T10:35:00",
    "durationMs": 300000,
    "executionId": "uuid",
    "status": "COMPLETED",
    "completionReason": "STOP_LOSS_HIT",
    "spotPriceAtEntry": 24500.0,
    "atmStrike": 24500.0,
    "legs": [
      {
        "tradingSymbol": "NIFTY25NOV24500CE",
        "optionType": "CE",
        "strike": 24500.0,
        "quantity": 50,
        "entryPrice": 150.0,
        "exitPrice": 140.0,
        "entryTime": "2025-11-22T09:15:00",
        "exitTime": "2025-11-22T10:30:00",
        "profitLoss": -500.0,
        "profitLossPercentage": -6.67
      },
      {
        "tradingSymbol": "NIFTY25NOV24500PE",
        "optionType": "PE",
        "strike": 24500.0,
        "quantity": 50,
        "entryPrice": 145.0,
        "exitPrice": 135.0,
        "entryTime": "2025-11-22T09:15:00",
        "exitTime": "2025-11-22T10:30:00",
        "profitLoss": -500.0,
        "profitLossPercentage": -6.90
      }
    ],
    "performanceMetrics": {
      "totalPremiumPaid": 14750.0,
      "totalPremiumReceived": 13750.0,
      "grossProfitLoss": -1000.0,
      "charges": 150.0,
      "netProfitLoss": -1150.0,
      "returnPercentage": -7.80,
      "returnOnInvestment": -7.80,
      "maxDrawdown": -1500.0,
      "maxProfit": 200.0,
      "numberOfTrades": 4,
      "holdingDurationMs": 4500000,
      "holdingDurationFormatted": "1h 15m 0s"
    },
    "tradeEvents": [
      {
        "timestamp": "2025-11-22T09:15:00",
        "eventType": "ENTRY",
        "description": "Strategy executed - positions entered",
        "prices": {
          "NIFTY25NOV24500CE": 150.0,
          "NIFTY25NOV24500PE": 145.0
        },
        "totalValue": 14750.0,
        "unrealizedPnL": 0.0
      },
      {
        "timestamp": "2025-11-22T10:30:00",
        "eventType": "EXIT",
        "description": "Strategy completed - STOP_LOSS_HIT",
        "prices": {},
        "totalValue": 13750.0,
        "unrealizedPnL": -1000.0
      }
    ]
  }
}
```

### Execute Batch Backtest

**Endpoint:** `POST /api/backtest/batch`

**Request Body:**
```json
{
  "backtests": [
    {
      "strategyType": "ATM_STRADDLE",
      "instrumentType": "NIFTY",
      "expiry": "2025-11-28",
      "lots": 1,
      "stopLossPoints": 10.0,
      "targetPoints": 15.0
    },
    {
      "strategyType": "ATM_STRADDLE",
      "instrumentType": "BANKNIFTY",
      "expiry": "2025-11-27",
      "lots": 1,
      "stopLossPoints": 15.0,
      "targetPoints": 20.0
    }
  ],
  "runSequentially": false
}
```

**Parameters:**
- `backtests` (required): Array of backtest requests
- `startDate`: Start date for batch backtest (optional)
- `endDate`: End date for batch backtest (optional)
- `runSequentially`: Run backtests sequentially (default: false, runs in parallel)

**Response:**
```json
{
  "status": "success",
  "message": "Batch backtest completed: 2 successful, 0 failed",
  "data": {
    "batchId": "uuid",
    "startTime": "2025-11-23T10:00:00",
    "endTime": "2025-11-23T10:10:00",
    "totalDurationMs": 600000,
    "totalBacktests": 2,
    "successfulBacktests": 2,
    "failedBacktests": 0,
    "results": [
      // Array of individual BacktestResponse objects
    ],
    "aggregateStatistics": {
      "totalNetPnL": 2500.0,
      "averageNetPnL": 1250.0,
      "totalReturnPercentage": 15.5,
      "averageReturnPercentage": 7.75,
      "totalWins": 1,
      "totalLosses": 1,
      "winRate": 50.0,
      "bestReturn": 12.5,
      "worstReturn": 3.0,
      "averageHoldingDurationMs": 5400000,
      "averageHoldingDurationFormatted": "1h 30m 0s"
    }
  }
}
```

### Get Backtest Execution

**Endpoint:** `GET /api/backtest/{backtestId}`

Retrieve detailed results of a specific backtest execution.

### Health Check

**Endpoint:** `GET /api/backtest/health`

Check if backtesting service is available.

## Usage Examples

### Example 1: Basic Backtest

```javascript
// Test ATM Straddle on NIFTY for latest previous trading day
const response = await fetch('http://localhost:8080/api/backtest/execute', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-User-Id': 'user123'
  },
  body: JSON.stringify({
    strategyType: 'ATM_STRADDLE',
    instrumentType: 'NIFTY',
    expiry: '2025-11-28',
    lots: 1,
    stopLossPoints: 10.0,
    targetPoints: 15.0
  })
});

const result = await response.json();
console.log('Net P&L:', result.data.performanceMetrics.netProfitLoss);
console.log('Return %:', result.data.performanceMetrics.returnPercentage);
```

### Example 2: Backtest on Specific Date

```javascript
// Test strategy on a specific historical date
const response = await fetch('http://localhost:8080/api/backtest/execute', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-User-Id': 'user123'
  },
  body: JSON.stringify({
    strategyType: 'ATM_STRADDLE',
    instrumentType: 'BANKNIFTY',
    expiry: '2025-11-20',
    lots: 2,
    stopLossPoints: 15.0,
    targetPoints: 20.0,
    backtestDate: '2025-11-15', // Specific date
    includeDetailedLogs: true
  })
});
```

### Example 3: Batch Backtest for Parameter Optimization

```javascript
// Test multiple parameter combinations
const response = await fetch('http://localhost:8080/api/backtest/batch', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-User-Id': 'user123'
  },
  body: JSON.stringify({
    backtests: [
      {
        strategyType: 'ATM_STRADDLE',
        instrumentType: 'NIFTY',
        expiry: '2025-11-28',
        lots: 1,
        stopLossPoints: 10.0,
        targetPoints: 15.0
      },
      {
        strategyType: 'ATM_STRADDLE',
        instrumentType: 'NIFTY',
        expiry: '2025-11-28',
        lots: 1,
        stopLossPoints: 15.0,
        targetPoints: 20.0
      },
      {
        strategyType: 'ATM_STRADDLE',
        instrumentType: 'NIFTY',
        expiry: '2025-11-28',
        lots: 1,
        stopLossPoints: 20.0,
        targetPoints: 25.0
      }
    ],
    runSequentially: false
  })
});

const result = await response.json();
console.log('Win Rate:', result.data.aggregateStatistics.winRate + '%');
console.log('Best Return:', result.data.aggregateStatistics.bestReturn + '%');
console.log('Average P&L:', result.data.aggregateStatistics.averageNetPnL);
```

## Python Example

```python
import requests

# Backtest ATM Straddle
url = 'http://localhost:8080/api/backtest/execute'
headers = {
    'Content-Type': 'application/json',
    'X-User-Id': 'user123'
}
payload = {
    'strategyType': 'ATM_STRADDLE',
    'instrumentType': 'NIFTY',
    'expiry': '2025-11-28',
    'lots': 1,
    'stopLossPoints': 10.0,
    'targetPoints': 15.0
}

response = requests.post(url, headers=headers, json=payload)
result = response.json()

if result['status'] == 'success':
    metrics = result['data']['performanceMetrics']
    print(f"Net P&L: ₹{metrics['netProfitLoss']}")
    print(f"Return: {metrics['returnPercentage']:.2f}%")
    print(f"Win Rate: {result['data']['aggregateStatistics']['winRate']:.2f}%")
else:
    print(f"Backtest failed: {result.get('message')}")
```

## Important Notes

1. **Paper Trading Mode Required**: Backtesting only works when `trading.paper-trading-enabled=true` in `application.yml`

2. **Historical Data**: Uses minute-level candle data from Zerodha and interpolates to per-second prices

3. **Latest Previous Trading Day**: By default, backtests run on the most recent completed trading day (not today)

4. **Realistic Simulation**: Includes:
   - Brokerage charges
   - STT (Securities Transaction Tax)
   - Transaction charges
   - GST on charges
   - SEBI charges
   - Stamp duty
   - Execution delays and slippage (configurable)

5. **Multi-User Support**: Each backtest is isolated per user via the `X-User-Id` header

6. **Performance**: Batch backtests run in parallel by default for faster execution

## Configuration

Add to `application.yml`:

```yaml
trading:
  paper-trading-enabled: true  # Required for backtesting

historical:
  replay:
    sleep-millis-per-second: 0  # 0 for fastest replay, increase for slower simulation
```

## Troubleshooting

### Error: "Backtesting requires paper trading mode"
**Solution**: Set `trading.paper-trading-enabled: true` in `application.yml`

### Error: "No expiry dates available"
**Solution**: Ensure the backtest date is valid and has available option contracts

### Slow Backtest Execution
**Solution**: 
- Use `replaySpeedMultiplier: 0` for fastest execution
- Run batch backtests in parallel (default behavior)
- Disable detailed logs: `includeDetailedLogs: false`

### Missing Historical Data
**Solution**: Ensure you have valid Kite Connect API credentials and the instrument had trading activity on the backtest date

## Future Enhancements

- [ ] Support for date range backtesting (multiple days)
- [ ] Walk-forward analysis
- [ ] Monte Carlo simulation
- [ ] Strategy optimization algorithms
- [ ] Export results to CSV/Excel
- [ ] Visualization charts and graphs
- [ ] Comparison reports across strategies
- [ ] Risk metrics (Sharpe ratio, Sortino ratio, etc.)

