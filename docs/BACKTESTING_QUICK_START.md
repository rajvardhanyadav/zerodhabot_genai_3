# Backtesting Quick Start Guide

## Prerequisites

1. Ensure paper trading mode is enabled in `application.yml`:
```yaml
trading:
  paper-trading-enabled: true
```

2. Application is running:
```powershell
.\mvnw.cmd spring-boot:run
```

3. You have a valid Kite Connect session (login via `/api/auth/login`)

## Quick Examples

### Example 1: Simple Backtest (Latest Trading Day)

Test ATM Straddle on NIFTY using the latest previous trading day:

**cURL:**
```bash
curl -X POST http://localhost:8080/api/backtest/execute \
  -H "Content-Type: application/json" \
  -H "X-User-Id: user123" \
  -d '{
    "strategyType": "ATM_STRADDLE",
    "instrumentType": "NIFTY",
    "expiry": "2025-11-28",
    "lots": 1,
    "stopLossPoints": 10.0,
    "targetPoints": 15.0
  }'
```

**JavaScript/Fetch:**
```javascript
const backtest = async () => {
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
  
  if (result.status === 'success') {
    const metrics = result.data.performanceMetrics;
    console.log('📊 Backtest Results:');
    console.log(`Net P&L: ₹${metrics.netProfitLoss.toFixed(2)}`);
    console.log(`Return: ${metrics.returnPercentage.toFixed(2)}%`);
    console.log(`Max Profit: ₹${metrics.maxProfit.toFixed(2)}`);
    console.log(`Max Drawdown: ₹${metrics.maxDrawdown.toFixed(2)}`);
    console.log(`Holding Time: ${metrics.holdingDurationFormatted}`);
    console.log(`Completion: ${result.data.completionReason}`);
  }
};

backtest();
```

**Python:**
```python
import requests
import json

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
    print('📊 Backtest Results:')
    print(f"Net P&L: ₹{metrics['netProfitLoss']:.2f}")
    print(f"Return: {metrics['returnPercentage']:.2f}%")
    print(f"Max Profit: ₹{metrics['maxProfit']:.2f}")
    print(f"Max Drawdown: ₹{metrics['maxDrawdown']:.2f}")
    print(f"Holding Time: {metrics['holdingDurationFormatted']}")
    print(f"Completion: {result['data']['completionReason']}")
```

### Example 2: Backtest on Specific Date

```javascript
const response = await fetch('http://localhost:8080/api/backtest/execute', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-User-Id': 'user123'
  },
  body: JSON.stringify({
    strategyType: 'ATM_STRADDLE',
    instrumentType': 'BANKNIFTY',
    expiry: '2025-11-20',
    lots: 2,
    stopLossPoints: 15.0,
    targetPoints: 20.0,
    backtestDate: '2025-11-15',  // Specific date
    includeDetailedLogs: true    // Get detailed tick-by-tick logs
  })
});
```

### Example 3: Batch Backtest - Parameter Optimization

Test multiple stop-loss and target combinations:

```javascript
const batchBacktest = async () => {
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
      runSequentially: false  // Run in parallel
    })
  });

  const result = await response.json();
  
  if (result.status === 'success') {
    const stats = result.data.aggregateStatistics;
    console.log('📈 Batch Backtest Results:');
    console.log(`Total Backtests: ${result.data.totalBacktests}`);
    console.log(`Successful: ${result.data.successfulBacktests}`);
    console.log(`Failed: ${result.data.failedBacktests}`);
    console.log(`\nAggregate Statistics:`);
    console.log(`Total P&L: ₹${stats.totalNetPnL.toFixed(2)}`);
    console.log(`Average P&L: ₹${stats.averageNetPnL.toFixed(2)}`);
    console.log(`Win Rate: ${stats.winRate.toFixed(2)}%`);
    console.log(`Best Return: ${stats.bestReturn.toFixed(2)}%`);
    console.log(`Worst Return: ${stats.worstReturn.toFixed(2)}%`);
    
    // Show individual results
    console.log(`\nIndividual Results:`);
    result.data.results.forEach((bt, index) => {
      console.log(`\nBacktest ${index + 1}:`);
      console.log(`  SL: ${bt.performanceMetrics.stopLossPoints}, Target: ${bt.performanceMetrics.targetPoints}`);
      console.log(`  P&L: ₹${bt.performanceMetrics.netProfitLoss.toFixed(2)}`);
      console.log(`  Return: ${bt.performanceMetrics.returnPercentage.toFixed(2)}%`);
    });
  }
};

batchBacktest();
```

### Example 4: ATM Strangle Backtest

```javascript
const response = await fetch('http://localhost:8080/api/backtest/execute', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-User-Id': 'user123'
  },
  body: JSON.stringify({
    strategyType: 'ATM_STRANGLE',
    instrumentType: 'NIFTY',
    expiry: '2025-11-28',
    lots: 1,
    strikeGap: 100,  // OTM gap
    stopLossPoints: 8.0,
    targetPoints: 12.0
  })
});
```

## Understanding the Results

### Performance Metrics
```json
{
  "performanceMetrics": {
    "totalPremiumPaid": 14750.0,      // Total entry cost
    "totalPremiumReceived": 13750.0,  // Total exit value
    "grossProfitLoss": -1000.0,       // Before charges
    "charges": 150.0,                 // All trading charges
    "netProfitLoss": -1150.0,         // Final P&L after charges
    "returnPercentage": -7.80,        // Net return %
    "returnOnInvestment": -7.80,      // ROI
    "maxDrawdown": -1500.0,           // Worst unrealized loss
    "maxProfit": 200.0,               // Best unrealized profit
    "numberOfTrades": 4,              // Total orders (entry + exit)
    "holdingDurationMs": 4500000,     // Time held in ms
    "holdingDurationFormatted": "1h 15m 0s"
  }
}
```

### Completion Reasons
- `STOP_LOSS_HIT` - Stop loss triggered
- `TARGET_HIT` - Target profit achieved
- `PRICE_DIFF_ALL_LEGS` - Price difference threshold hit
- `PRICE_DIFF_INDIVIDUAL` - Individual leg closed due to loss
- `MANUAL_EXIT` - Manually closed
- `AUTO_SQUARE_OFF` - Auto square-off time reached
- `OTHER` - Other reasons

### Leg Details
```json
{
  "legs": [
    {
      "tradingSymbol": "NIFTY25NOV24500CE",
      "optionType": "CE",
      "strike": 24500.0,
      "quantity": 50,
      "entryPrice": 150.0,
      "exitPrice": 140.0,
      "profitLoss": -500.0,
      "profitLossPercentage": -6.67
    }
  ]
}
```

## Common Use Cases

### 1. Test Before Live Trading
Run a backtest before executing a strategy live:
```javascript
// Backtest first
const backtestResult = await backtest(strategyParams);
if (backtestResult.data.performanceMetrics.returnPercentage > 5) {
  // If backtest is profitable, execute live
  await executeLive(strategyParams);
}
```

### 2. Parameter Optimization
Find optimal stop-loss and target:
```javascript
const params = [];
for (let sl = 5; sl <= 20; sl += 5) {
  for (let target = 10; target <= 30; target += 5) {
    params.push({
      strategyType: 'ATM_STRADDLE',
      instrumentType: 'NIFTY',
      expiry: '2025-11-28',
      lots: 1,
      stopLossPoints: sl,
      targetPoints: target
    });
  }
}

const batchResult = await batchBacktest(params);
// Analyze which combination gives best return
```

### 3. Strategy Comparison
Compare different strategies:
```javascript
const strategies = [
  { strategyType: 'ATM_STRADDLE', instrumentType: 'NIFTY' },
  { strategyType: 'ATM_STRANGLE', instrumentType: 'NIFTY', strikeGap: 100 },
  { strategyType: 'ATM_STRADDLE', instrumentType: 'BANKNIFTY' }
];

const results = await batchBacktest(strategies);
// Compare which strategy performs best
```

## Tips for Better Backtesting

1. **Use Realistic Parameters**: Test with parameters you would actually use in live trading

2. **Multiple Days**: Run backtests on different dates to validate consistency

3. **Account for Charges**: Net P&L already includes all charges - don't add them again

4. **Understand Completion Reason**: Know why the strategy exited

5. **Check Max Drawdown**: Ensure you can handle the worst-case loss

6. **Batch Test for Confidence**: Run multiple backtests to see if results are consistent

7. **Paper Trade First**: After backtesting, test in paper mode before going live

## Troubleshooting

### Error: "Backtesting requires paper trading mode"
```yaml
# Fix in application.yml
trading:
  paper-trading-enabled: true
```

### No Historical Data
- Ensure the date is a valid trading day (not weekend/holiday)
- Check that the instrument had trading activity
- Verify Kite Connect API credentials are valid

### Slow Execution
```yaml
# Speed up in application.yml
historical:
  replay:
    sleep-millis-per-second: 0  # Fastest
```

For detailed documentation, see [BACKTESTING_FEATURE.md](BACKTESTING_FEATURE.md)

