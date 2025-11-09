# Paper Trading Feature - Complete Documentation

## Overview

The paper trading feature allows you to test trading strategies with **real-time market data from Kite API** without risking actual money. All orders are simulated in-memory while using actual market prices.

## Key Features

### ✅ Implemented Features

1. **Real-time Market Data Integration**
   - Uses Kite API for live price feeds
   - Real-time LTP (Last Traded Price) for order execution
   - Same market data as live trading

2. **Complete Order Management**
   - Place orders (MARKET, LIMIT, SL, SL-M)
   - Modify pending orders
   - Cancel orders
   - Track order history

3. **Realistic Trading Simulation**
   - Configurable slippage on market orders
   - Realistic brokerage and tax calculations
   - Margin management (CNC, MIS, NRML)
   - Order execution delays
   - Random order rejection simulation (optional)

4. **Position & P&L Tracking**
   - Real-time position updates
   - Realized and unrealized P&L
   - Day trading statistics
   - Buy/sell average calculations

5. **Virtual Account Management**
   - Configurable initial balance (default: ₹10,00,000)
   - Available balance tracking
   - Margin blocking and release
   - Total brokerage and tax tracking

6. **Trading Statistics**
   - Total trades count
   - Win/loss ratio
   - Win rate percentage
   - Total P&L (realized + unrealized)
   - Brokerage and tax breakdown

## Configuration

### Switching Between Modes

Edit `application.yml`:

```yaml
trading:
  # Set to true for Paper Trading, false for Live Trading
  paper-trading-enabled: true
```

**Important:** This is a server-level configuration. Restart the application after changing this setting.

### Paper Trading Parameters

```yaml
trading:
  # Initial virtual balance
  initial-balance: 1000000.0  # ₹10 Lakhs
  
  # Brokerage and charges (realistic simulation)
  apply-brokerage-charges: true
  brokerage-per-order: 20.0
  stt-percentage: 0.025
  transaction-charges: 0.00325
  gst-percentage: 18.0
  sebi-charges: 0.0001
  stamp-duty: 0.003
  
  # Execution simulation
  slippage-percentage: 0.05  # 0.05% slippage on market orders
  enable-execution-delay: true
  execution-delay-ms: 500  # 500ms delay to simulate exchange
  enable-order-rejection: false  # Random rejection simulation
  rejection-probability: 0.02  # 2% rejection rate
```

## API Endpoints

### Check Trading Mode

```http
GET /api/paper-trading/status
```

**Response:**
```json
{
  "status": "success",
  "data": {
    "paperTradingEnabled": true,
    "mode": "PAPER_TRADING",
    "description": "Simulated trading with virtual money"
  }
}
```

### Get Paper Account Details

```http
GET /api/paper-trading/account
```

**Response:**
```json
{
  "status": "success",
  "message": "Paper trading account details",
  "data": {
    "userId": "PAPER_USER",
    "availableBalance": 985432.50,
    "usedMargin": 14567.50,
    "totalBalance": 1000000.00,
    "totalRealisedPnL": 1250.75,
    "totalUnrealisedPnL": -318.25,
    "todaysPnL": 932.50,
    "totalTrades": 12,
    "winningTrades": 8,
    "losingTrades": 4,
    "totalBrokerage": 240.00,
    "totalTaxes": 127.50
  }
}
```

### Get Trading Statistics

```http
GET /api/paper-trading/statistics
```

**Response:**
```json
{
  "status": "success",
  "data": {
    "totalTrades": 12,
    "winningTrades": 8,
    "losingTrades": 4,
    "winRate": 66.67,
    "totalRealisedPnL": 1250.75,
    "totalUnrealisedPnL": -318.25,
    "todaysPnL": 932.50,
    "totalBrokerage": 240.00,
    "totalTaxes": 127.50,
    "netPnL": 883.25,
    "availableBalance": 985432.50,
    "usedMargin": 14567.50,
    "totalBalance": 1000000.00
  }
}
```

### Reset Paper Account

```http
POST /api/paper-trading/account/reset
```

**Response:**
```json
{
  "status": "success",
  "message": "Paper trading account reset successfully"
}
```

### Place Order (Works in Both Modes)

```http
POST /api/orders
```

**Request Body:**
```json
{
  "tradingSymbol": "RELIANCE",
  "exchange": "NSE",
  "transactionType": "BUY",
  "quantity": 10,
  "product": "MIS",
  "orderType": "MARKET",
  "validity": "DAY"
}
```

The same endpoint automatically routes to:
- **Paper Trading** if `paper-trading-enabled: true`
- **Live Trading** if `paper-trading-enabled: false`

## How It Works

### Order Flow in Paper Trading

1. **Order Placement**
   - Validates order parameters
   - Fetches real-time price from Kite API
   - Checks available balance
   - Blocks required margin for BUY orders
   - Returns order ID immediately

2. **Async Order Execution**
   - Simulates exchange delay (500ms default)
   - Optional rejection simulation (2% probability)
   - Re-fetches current market price
   - Applies slippage for market orders
   - Executes order based on type

3. **Position Update**
   - Updates buy/sell quantities
   - Calculates average prices
   - Computes realized P&L on square-off
   - Releases margin on position closure
   - Updates account statistics

4. **Charge Calculation**
   - Brokerage: ₹20 per order
   - STT: 0.025% on sell side
   - Transaction charges: 0.00325%
   - GST on brokerage: 18%
   - SEBI charges: 0.0001%
   - Stamp duty: 0.003%

## Margin Requirements

| Product Type | Margin Required |
|--------------|-----------------|
| CNC (Delivery) | 100% of order value |
| MIS (Intraday) | 20% of order value |
| NRML (Normal) | 40% of order value |

## Advantages of Paper Trading

### ✅ Benefits

1. **Risk-Free Testing**
   - Test strategies without financial risk
   - Learn order types and execution
   - Practice portfolio management

2. **Real Market Conditions**
   - Uses actual market prices from Kite API
   - Real-time data integration
   - Same API responses as live trading

3. **Performance Analysis**
   - Track win/loss ratio
   - Analyze P&L patterns
   - Identify strategy weaknesses

4. **Strategy Validation**
   - Test new trading strategies
   - Optimize parameters
   - Build confidence before going live

5. **Seamless Transition**
   - Same API endpoints as live trading
   - No code changes required to switch modes
   - Identical request/response formats

## Limitations & Considerations

### ⚠️ Important Notes

1. **In-Memory Storage**
   - All data is stored in memory
   - Data is lost on application restart
   - Not suitable for long-term backtesting

2. **Simplified Order Execution**
   - LIMIT orders execute immediately if price matches
   - No order book simulation
   - Partial fills not simulated

3. **Market Impact**
   - No consideration for order book depth
   - Large orders execute at single price
   - No market impact modeling

4. **Single User**
   - Currently supports single user (PAPER_USER)
   - No multi-user session management

5. **No Overnight Positions**
   - Positions don't carry forward across restarts
   - No end-of-day settlement simulation

## Best Practices

### 📋 Recommendations

1. **Start with Paper Trading**
   - Always test strategies in paper mode first
   - Validate all order types work correctly
   - Ensure P&L calculations are understood

2. **Realistic Configuration**
   - Use actual brokerage charges
   - Enable slippage simulation
   - Set initial balance to your actual capital

3. **Monitor Statistics**
   - Track win rate regularly
   - Analyze P&L patterns
   - Review brokerage impact on profitability

4. **Switch to Live Carefully**
   - Only after successful paper trading
   - Start with small position sizes
   - Monitor closely initially

## Switching to Live Trading

### Steps to Go Live

1. **Update Configuration**
   ```yaml
   trading:
     paper-trading-enabled: false
   ```

2. **Restart Application**
   ```bash
   mvn spring-boot:run
   ```

3. **Verify Mode**
   ```http
   GET /api/paper-trading/status
   ```

4. **Start Trading**
   - Same API endpoints
   - Orders now go to actual exchange
   - Real money at risk

## Additional Functionality Suggestions

### 🚀 Future Enhancements

Consider adding these features:

1. **Persistence**
   - Save paper trading data to database
   - Historical P&L tracking
   - Trade journal

2. **Advanced Statistics**
   - Sharpe ratio calculation
   - Maximum drawdown tracking
   - Average holding period

3. **Multiple Accounts**
   - Support multiple paper accounts
   - Compare strategy performance
   - User authentication integration

4. **Backtesting**
   - Historical data replay
   - Strategy optimization
   - Walk-forward analysis

5. **Order Book Simulation**
   - Realistic limit order matching
   - Partial fill simulation
   - Market depth consideration

6. **Reporting**
   - Daily P&L reports
   - Trade summary exports
   - Performance dashboards

7. **Alerts & Notifications**
   - Email notifications for trades
   - Slack/Telegram integration
   - Daily summary reports

8. **Risk Management**
   - Position size limits
   - Daily loss limits
   - Margin call simulation

## Troubleshooting

### Common Issues

**Issue: Orders not executing**
- Check if paper trading is enabled
- Verify market data is available
- Check logs for errors

**Issue: Insufficient funds**
- Check available balance: `GET /api/paper-trading/account`
- Verify margin requirements for product type
- Reset account if needed

**Issue: Can't access paper trading endpoints**
- Verify `paper-trading-enabled: true` in config
- Restart application after config change
- Check endpoint URLs

## Support

For issues or questions:
1. Check application logs (`logging.level.com.tradingbot: DEBUG`)
2. Review error messages in API responses
3. Verify configuration in `application.yml`
4. Test with simple market orders first

---

**Remember:** Paper trading is for testing and learning only. Always thoroughly test your strategies before risking real money in live trading.

