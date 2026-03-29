# Zerodha Trading Bot

[![Java 17](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot 3.2.0](https://img.shields.io/badge/Spring%20Boot-3.2.0-green.svg)](https://spring.io/projects/spring-boot)
[![Version 4.2](https://img.shields.io/badge/Version-4.2-blue.svg)]()

A **Spring Boot backend** for automated options trading using **Zerodha Kite Connect API**. Supports live trading, paper trading, real-time position monitoring, and multi-user isolation.

## Features

- 🔐 OAuth authentication with Kite Connect
- 📊 Order management (place, modify, cancel)
- 💼 Portfolio tracking (positions, holdings, P&L)
- 📈 Real-time market data (quotes, OHLC, LTP, historical)
- 🎯 Trading strategies (ATM Straddle, Sell ATM Straddle)
- 🔄 Real-time WebSocket position monitoring
- 📝 Paper trading with realistic simulation
- 👥 Multi-user isolation via `X-User-Id` header
- 💾 Async data persistence (H2/PostgreSQL)
- 🚀 GTT (Good Till Triggered) orders

---

## Quick Start

### Prerequisites
- Java 17+
- Maven 3.6+
- [Kite Connect API](https://kite.trade/) credentials

### Setup

```bash
# Clone and configure
git clone <repository-url>
cd zerodhabot_genai_3

# Create local config
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
# Edit with your Kite API key/secret
```

### Configuration (`application-local.yml`)

```yaml
kite:
  api-key: YOUR_KITE_API_KEY
  api-secret: YOUR_KITE_API_SECRET

trading:
  paper-trading-enabled: true  # Start with paper trading
```

### Build & Run

```bash
./mvnw clean package -DskipTests
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

### Access
- **API**: http://localhost:8080
- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **H2 Console**: http://localhost:8080/h2-console

---

## Authentication Flow

```
1. GET /api/auth/login-url     → Get Kite OAuth URL
2. User logs in on Kite        → Redirected with request_token
3. POST /api/auth/session      → Exchange token for session
4. Use X-User-Id header        → All subsequent requests
5. POST /api/auth/logout       → Logout and cleanup (v4.2)
```

---

## API Overview

| Group | Base Path | Key Endpoints |
|-------|-----------|---------------|
| Auth | `/api/auth` | `login-url`, `session`, `profile`, `logout` |
| Orders | `/api/orders` | Place, modify, cancel, list |
| Portfolio | `/api/portfolio` | `positions`, `holdings` |
| Market | `/api/market` | `quote`, `ohlc`, `ltp`, `historical` |
| Strategies | `/api/strategies` | `execute`, `active`, `stop-all`, `bot-status` |
| Monitoring | `/api/monitoring` | `connect`, `disconnect`, `status` |
| Paper Trading | `/api/paper-trading` | `status`, `mode`, `reset` |
| History | `/api/history` | `trades`, `strategies`, `daily-summary` |
| GTT | `/api/gtt` | CRUD for GTT orders |

> 📖 **Full API Reference**: See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#api-reference)

---

## Trading Strategies

### Execute ATM Straddle
```bash
curl -X POST http://localhost:8080/api/strategies/execute \
  -H "Content-Type: application/json" \
  -H "X-User-Id: user-123" \
  -d '{
    "strategyType": "ATM_STRADDLE",
    "instrumentType": "NIFTY",
    "expiry": "2025-12-26",
    "lots": 1,
    "stopLossPoints": 20.0,
    "targetPoints": 30.0
  }'
```

**Available Strategies:**
- `ATM_STRADDLE` - Buy ATM Call + Put (profits from volatility)
- `SELL_ATM_STRADDLE` - Sell ATM Call + Put (profits from low volatility)

---

## Multi-User Support

All requests require `X-User-Id` header for user isolation:

```http
X-User-Id: user-123
```

Each user has isolated: sessions, WebSocket connections, paper trading accounts, position monitors.

---

## Project Structure

```
src/main/java/com/tradingbot/
├── config/        # Spring configuration
├── controller/    # REST API endpoints
├── dto/           # Data Transfer Objects
├── entity/        # JPA entities
├── service/       # Business logic
│   ├── strategy/  # Trading strategies
│   └── pnl/       # P&L calculation
└── util/          # Utilities
```

---

## Documentation

| File | Purpose |
|------|---------|
| [README.md](README.md) | Quick start (this file) |
| [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md) | Comprehensive architecture & AI agent context |
| [docs/BACKTEST_FRONTEND_INTEGRATION.md](docs/BACKTEST_FRONTEND_INTEGRATION.md) | Backtest API integration guide |
| [docs/MARKET_ANALYSIS_API_SPEC.md](docs/MARKET_ANALYSIS_API_SPEC.md) | Neutral market logs API spec |
| [docs/LAZY_AUDIT.md](docs/LAZY_AUDIT.md) | `@Lazy` circular dependency audit |

---

## Troubleshooting

| Issue | Solution |
|-------|----------|
| "No active session" | Ensure X-User-Id matches session |
| WebSocket fails | Check session with `/api/auth/profile` |
| Strategy fails | Check margin with `/api/account/margins/NFO` |

Enable debug logging:
```yaml
logging:
  level:
    com.tradingbot: DEBUG
```

---

## Development

```bash
./mvnw test                           # Run tests
./mvnw clean package -DskipTests      # Build JAR
docker build -t zerodha-trading-bot . # Docker build
```

---

**Version 4.1** | **December 2025**

