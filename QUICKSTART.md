# Quick Start Guide - Zerodha Trading Bot

## Project Successfully Created! ✅

Your Spring Boot trading bot backend application has been successfully generated with the following structure:

## 📁 Project Structure

```
zerodhabot_genai_3/
├── pom.xml (Maven configuration with all dependencies)
├── README.md (Comprehensive documentation)
├── .gitignore
├── src/
│   ├── main/
│   │   ├── java/com/tradingbot/
│   │   │   ├── TradingBotApplication.java (Main Spring Boot class)
│   │   │   ├── config/
│   │   │   │   ├── KiteConfig.java (Kite Connect configuration)
│   │   │   │   └── SwaggerConfig.java (API documentation config)
│   │   │   ├── controller/
│   │   │   │   ├── AuthController.java (Authentication endpoints)
│   │   │   │   ├── OrderController.java (Order management)
│   │   │   │   ├── PortfolioController.java (Positions & holdings)
│   │   │   │   ├── MarketDataController.java (Market data & quotes)
│   │   │   │   ├── AccountController.java (Account & margins)
│   │   │   │   ├── GTTController.java (GTT orders)
│   │   │   │   └── HealthController.java (Health check)
│   │   │   ├── service/
│   │   │   │   └── TradingService.java (Business logic)
│   │   │   ├── dto/
│   │   │   │   ├── OrderRequest.java
│   │   │   │   ├── OrderResponse.java
│   │   │   │   ├── LoginRequest.java
│   │   │   │   └── ApiResponse.java
│   │   │   └── exception/
│   │   │       └── GlobalExceptionHandler.java
│   │   └── resources/
│   │       ├── application.yml (Main configuration)
│   │       └── application-local.yml.example
│   └── test/
│       └── java/
```

## 🚀 Prerequisites & Setup

### 1. Install Required Software

You need to install the following:

1. **Java 21** (Download from: https://www.oracle.com/java/technologies/downloads/)
2. **Maven** (Download from: https://maven.apache.org/download.cgi)
   - After installation, add Maven's bin directory to your PATH
   - Or use IntelliJ IDEA's embedded Maven

### 2. Get Kite Connect Credentials

1. Visit https://kite.trade/
2. Create a developer account and a new app
3. Note down your **API Key** and **API Secret**

### 3. Configure Your Application

Edit `src/main/resources/application.yml` and update:

```yaml
kite:
  api-key: YOUR_API_KEY_HERE
  api-secret: YOUR_API_SECRET_HERE
```

Or set environment variables:
```cmd
set KITE_API_KEY=your_api_key
set KITE_API_SECRET=your_api_secret
```

## 🏃 Running the Application

### Option 1: Using IntelliJ IDEA (Recommended)

1. Open the project in IntelliJ IDEA
2. Wait for Maven to download dependencies (automatic)
3. Right-click on `TradingBotApplication.java`
4. Select "Run 'TradingBotApplication'"

### Option 2: Using Maven Command Line

```cmd
mvn spring-boot:run
```

### Option 3: Build JAR and Run

```cmd
mvn clean package -DskipTests
java -jar target\zerodhabot_genai_3-1.0-SNAPSHOT.jar
```

## 📋 API Endpoints

Once running, access:

- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **API Docs**: http://localhost:8080/api-docs
- **Health Check**: http://localhost:8080/api/health

## 🔐 Authentication Flow

1. **Get Login URL**
   ```
   GET http://localhost:8080/api/auth/login-url
   ```

2. **Open the URL in browser and login with Zerodha credentials**

3. **After login, you'll get a request_token in the redirect URL**

4. **Generate Session**
   ```
   POST http://localhost:8080/api/auth/session
   Content-Type: application/json
   
   {
     "requestToken": "your_request_token_here"
   }
   ```

## 📊 Key Features Implemented

### ✅ Authentication & User Management
- Kite Connect OAuth integration
- Session management
- User profile retrieval

### ✅ Order Management
- Place orders (MARKET, LIMIT, SL, SL-M)
- Modify existing orders
- Cancel orders
- View order book
- Order history tracking

### ✅ Portfolio Management
- View positions (day & net)
- View holdings
- Trade book
- Convert position product types

### ✅ Market Data
- Real-time quotes
- OHLC data
- Last Traded Price (LTP)
- Historical data
- Instrument master

### ✅ Account Management
- Margin information
- Account balance

### ✅ Advanced Features
- GTT (Good Till Triggered) orders
- Multiple exchange support (NSE, BSE, NFO, MCX, etc.)

### ✅ Additional Features
- Global exception handling
- API response wrapper
- Swagger/OpenAPI documentation
- Actuator health endpoints
- Comprehensive logging

## 📝 Example API Calls

### Place a Market Order
```bash
POST http://localhost:8080/api/orders
Content-Type: application/json

{
  "tradingSymbol": "INFY",
  "exchange": "NSE",
  "transactionType": "BUY",
  "quantity": 1,
  "product": "CNC",
  "orderType": "MARKET",
  "validity": "DAY"
}
```

### Get Market Quote
```bash
GET http://localhost:8080/api/market/quote?instruments=NSE:INFY&instruments=NSE:TCS
```

### Get Positions
```bash
GET http://localhost:8080/api/portfolio/positions
```

### Get Account Margins
```bash
GET http://localhost:8080/api/account/margins/equity
```

## 🛠️ Technology Stack

- ✅ **Java 21** - Latest LTS version
- ✅ **Spring Boot 3.2.0** - Modern Spring framework
- ✅ **Kite Connect Java SDK 3.2.0** - Official Zerodha SDK
- ✅ **Lombok** - Reduces boilerplate code
- ✅ **Swagger/OpenAPI** - Interactive API documentation
- ✅ **Maven** - Dependency management

## 📚 Documentation References

All Kite Connect SDK documentation has been integrated:
- Official SDK: https://kite.trade/docs/javakiteconnect/v3/
- API Docs: https://kite.trade/docs/connect/v3/

## ⚠️ Important Notes

1. **Never commit API credentials** to version control
2. The `.gitignore` file is already configured
3. **Test with small amounts** before production use
4. **Respect API rate limits**: 3 requests/second
5. **Trading involves financial risk** - use responsibly

## 🔧 Next Steps

1. Install Java 21 and Maven if not already installed
2. Configure your Kite API credentials
3. Run the application using IntelliJ IDEA or Maven
4. Access Swagger UI to explore and test all endpoints
5. Implement your trading strategies using the provided APIs

## 📞 Support

For issues with:
- **Kite Connect API**: kiteconnect@support.zerodha.com
- **API Documentation**: https://kite.trade/forum/

## ⚖️ Disclaimer

This software is for educational purposes. Always test thoroughly before deploying any trading strategy. The authors are not responsible for any financial losses.

---

**Your Spring Boot Trading Bot is ready to use!** 🎉

