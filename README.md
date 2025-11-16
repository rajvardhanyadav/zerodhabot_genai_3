# Zerodha Trading Bot - Backend API

A comprehensive Spring Boot backend application for automated trading using Zerodha's Kite Connect API.

## 📚 Complete Documentation

**For detailed API documentation, please refer to:** [COMPLETE_API_DOCUMENTATION.md](COMPLETE_API_DOCUMENTATION.md)

- Architecture overview: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

This comprehensive guide includes:
- Complete API Reference for all endpoints
- Trading Strategies documentation
- Paper Trading guide
- Position Monitoring with WebSocket
- Historical Replay (backtest-like)
- Auto-reentry behavior for ATM strategies (live & historical)
- Configuration options
- Code examples in JavaScript/TypeScript and Python
- Error handling and troubleshooting

## Features

- **Authentication**: Kite Connect OAuth integration
- **Order Management**: Place, modify, and cancel orders
- **Portfolio Management**: View positions, holdings, and trades
- **Market Data**: Real-time quotes, OHLC, LTP, and historical data
- **GTT Orders**: Good Till Triggered order management
- **Trading Strategies**: ATM Straddle, ATM Strangle with auto SL/Target and optional auto-reentry
- **Position Monitoring**: Real-time WebSocket-based monitoring (per-user)
- **Paper Trading**: Risk-free testing with real market data
- **Historical Replay**: Backtest-like execution using recent day's data (per-second replay) with the same auto-reentry logic
- **Order Charges**: Fetch brokerage/charges for executed orders today
- **API Documentation**: Interactive Swagger UI
- **Multi-User Support**: Per-user sessions, WebSockets, and paper trading via `X-User-Id` header
- **Runtime Mode Toggle**: Switch between paper and live trading via `/api/paper-trading/mode`

## Technology Stack

- **Java 17**
- **Spring Boot 3.2.0**
- **Kite Connect Java SDK 3.5.1**
- **Maven (Wrapper included)**
- **Swagger/OpenAPI** for API documentation
- **Lombok** for reducing boilerplate code
- **WebSocket** for real-time market data

## Quick Start

### Prerequisites

1. Java 17 or higher
2. Zerodha Kite Connect API credentials (API Key and API Secret)
3. Active Zerodha trading account

Note: Maven Wrapper is included; you do not need a global Maven installation.

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

Windows (PowerShell):
```powershell
# Build and run tests
./mvnw.cmd -DskipTests=false test

# Run the application
./mvnw.cmd spring-boot:run
```

macOS/Linux (bash):
```bash
# Build and run tests
./mvnw -DskipTests=false test

# Run the application
./mvnw spring-boot:run
```

The application will start on `http://localhost:8080`

4. **Access API Documentation**

Open Swagger UI: `http://localhost:8080/swagger-ui.html`

## Key Features

### 🎯 Trading Strategies
- **ATM Straddle**: Buy ATM Call + Buy ATM Put
- **ATM Strangle**: Buy OTM Call + Buy OTM Put
- Configurable Stop-Loss and Target
- Individual leg exit capability
- Delta-based strike selection using Black-Scholes model
- **Auto-Reentry**: Optional automatic reentry for ATM strategies on SL/Target hit

### 📈 Position Monitoring
- Real-time price updates via WebSocket (per-user connections)
- Automatic SL/Target execution
- P&L tracking for each leg
- Individual and full position exits

### 🧪 Paper Trading
- Test strategies with real market data
- No real money at risk
- Realistic order execution with slippage
- Complete position and P&L tracking

### 💾 Historical Replay
- Run strategies on the most recent trading day using per-second replay derived from minute candles
- Fast, asynchronous replay; uses the same monitoring and exit logic as live mode
- **Auto-Reentry**: Strategies automatically reenter positions based on historical data

### 💰 Order Charges
- Fetch brokerage and statutory charges for all executed orders today (from Kite charges API)

## API Endpoints Overview

- **Authentication**: `/api/auth/*`
- **Orders**: `/api/orders/*`
- **Portfolio**: `/api/portfolio/*`
- **Market Data**: `/api/market/*`
- **Account**: `/api/account/*` (e.g., `margins/{segment}`)
- **GTT Orders**: `/api/gtt/*`
- **Strategies**: `/api/strategies/*`
- **Monitoring**: `/api/monitoring/*`
- **Paper Trading**: `/api/paper-trading/*`
- **Trading Mode**: `/api/paper-trading/mode` (new)

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

Security note:
- Prefer environment variables for `KITE_API_KEY` and `KITE_API_SECRET`. Do not commit real credentials.

## Multi-User Support and X-User-Id header

This API is multi-tenant by design. Each request is processed under a specific user and all runtime state (Kite sessions, WebSocket connections, paper-trading accounts, orders, and positions) is isolated per user.

- Provide a header on every protected API call:
  - Header name: `X-User-Id`
  - Value: an opaque identifier for the calling user (e.g., an email, UUID, or username)
- Session creation (`POST /api/auth/session`) is flexible: if you omit `X-User-Id` the backend will infer the user id from the Kite response (`user.userId`) and store the session under that id. It will also return that id so you can reuse it.
- If you supply `X-User-Id` during session creation, that value becomes the key, allowing mapping to an external identity.
- A request filter propagates the header into a per-request context. Services read the context to segregate state.
- WebSocket connections are per user. Subscriptions and monitors are scoped to the current user and won’t affect others.
- Paper trading accounts and positions are maintained per user. Resetting one user does not impact others.

Example (PowerShell) with explicit header:
```powershell
$headers = @{ 'X-User-Id' = 'user-123' }
Invoke-RestMethod -Uri http://localhost:8080/api/auth/session -Method Post -Headers $headers -Body '{"requestToken":"<KITE_REQUEST_TOKEN>"}' -ContentType 'application/json'
```

Example without header (user id inferred from Kite):
```powershell
Invoke-RestMethod -Uri http://localhost:8080/api/auth/session -Method Post -Body '{"requestToken":"<KITE_REQUEST_TOKEN>"}' -ContentType 'application/json'
# Response data.userId => use this value as X-User-Id for subsequent calls
```

Swagger UI: All protected operations expose the `X-User-Id` header parameter so you can set it once and try requests interactively.

## License

This project is for educational and testing purposes only. Use at your own risk.

## Support

- Review the complete documentation: [COMPLETE_API_DOCUMENTATION.md](COMPLETE_API_DOCUMENTATION.md)
- Check the Swagger UI at `http://localhost:8080/swagger-ui.html`
- Verify your Kite Connect credentials are valid
- Ensure you're within market hours for trading operations
