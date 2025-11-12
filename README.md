# Zerodha Trading Bot - Backend API

A comprehensive Spring Boot backend application for automated trading using Zerodha's Kite Connect API.

## 📚 Complete Documentation

**For detailed API documentation, please refer to:** [COMPLETE_API_DOCUMENTATION.md](COMPLETE_API_DOCUMENTATION.md)

This comprehensive guide includes:
- Complete API Reference for all endpoints
- Trading Strategies documentation
- Paper Trading guide
- Position Monitoring with WebSocket
- Configuration options
- Code examples in JavaScript/TypeScript and Python
- Error handling and troubleshooting

## Features

- **Authentication**: Kite Connect OAuth integration
- **Order Management**: Place, modify, and cancel orders
- **Portfolio Management**: View positions, holdings, and trades
- **Market Data**: Real-time quotes, OHLC, LTP, and historical data
- **GTT Orders**: Good Till Triggered order management
- **Trading Strategies**: ATM Straddle, ATM Strangle with auto SL/Target
- **Position Monitoring**: Real-time WebSocket-based monitoring
- **Paper Trading**: Risk-free testing with real market data
- **Order Charges**: Calculate brokerage and charges before placing orders
- **API Documentation**: Interactive Swagger UI

## Technology Stack

- **Java 21**
- **Spring Boot 3.2.0**
- **Kite Connect Java SDK 3.2.0**
- **Maven**
- **Swagger/OpenAPI** for API documentation
- **Lombok** for reducing boilerplate code
- **WebSocket** for real-time market data

## Quick Start

### Prerequisites

1. Java 21 or higher
2. Maven 3.6+
3. Zerodha Kite Connect API credentials (API Key and API Secret)
4. Active Zerodha trading account

### Setup

1. **Get Kite Connect Credentials**
   - Visit [Kite Connect](https://kite.trade/)
   - Create a new app to get your API Key and API Secret

2. **Configure Application**

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

3. **Build and Run**

```cmd
# Build the project
mvn clean install

# Run the application
mvn spring-boot:run
```

The application will start on `http://localhost:8080`

4. **Access API Documentation**

Open Swagger UI: `http://localhost:8080/swagger-ui.html`

## Key Features

### 🎯 Trading Strategies
- **ATM Straddle**: Buy ATM Call + ATM Put
- **ATM Strangle**: Buy OTM Call + OTM Put
- Configurable Stop-Loss and Target
- Individual leg exit capability
- Delta-based strike selection using Black-Scholes model

### 📊 Position Monitoring
- Real-time price updates via WebSocket
- Automatic SL/Target execution
- P&L tracking for each leg
- Individual and full position exits

### 🧪 Paper Trading
- Test strategies with real market data
- No real money at risk
- Realistic order execution with slippage
- Complete position and P&L tracking

### 💰 Order Charges
- Calculate brokerage before placing orders
- STT, transaction charges, GST breakdown
- Break-even price calculation

## API Endpoints Overview

- **Authentication**: `/api/auth/*`
- **Orders**: `/api/orders/*`
- **Portfolio**: `/api/portfolio/*`
- **Market Data**: `/api/market/*`
- **Account**: `/api/account/*`
- **GTT Orders**: `/api/gtt/*`
- **Strategies**: `/api/strategies/*`
- **Monitoring**: `/api/monitoring/*`
- **Paper Trading**: `/api/paper-trading/*`

For complete API documentation with request/response examples, see [COMPLETE_API_DOCUMENTATION.md](COMPLETE_API_DOCUMENTATION.md)

## Configuration

Key configuration options in `application.yml`:

```yaml
# Trading Mode
trading:
  paper-trading-enabled: true  # Switch between paper and live trading

# Strategy Defaults
strategy:
  default-stop-loss-points: 10.0  # Default SL in points
  default-target-points: 15.0      # Default target in points
```

## License

This project is for educational and testing purposes only. Use at your own risk.

## Support

- Review the complete documentation: [COMPLETE_API_DOCUMENTATION.md](COMPLETE_API_DOCUMENTATION.md)
- Check the Swagger UI at `http://localhost:8080/swagger-ui.html`
- Verify your Kite Connect credentials are valid
- Ensure you're within market hours for trading operations
