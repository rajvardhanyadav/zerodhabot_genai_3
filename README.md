# Zerodha Trading Bot - Backend API

A comprehensive Spring Boot backend application for automated trading using Zerodha's Kite Connect API.

## Features

- **Authentication**: Kite Connect OAuth integration
- **Order Management**: Place, modify, and cancel orders
- **Portfolio Management**: View positions, holdings, and trades
- **Market Data**: Real-time quotes, OHLC, LTP, and historical data
- **GTT Orders**: Good Till Triggered order management
- **Account Information**: Margins and profile details
- **API Documentation**: Interactive Swagger UI

## Technology Stack

- **Java 21**
- **Spring Boot 3.2.0**
- **Kite Connect Java SDK 3.2.0**
- **Maven**
- **Swagger/OpenAPI** for API documentation
- **Lombok** for reducing boilerplate code

## Prerequisites

1. Java 21 or higher
2. Maven 3.6+
3. Zerodha Kite Connect API credentials (API Key and API Secret)
4. Active Zerodha trading account

## Setup Instructions

### 1. Get Kite Connect Credentials

1. Visit [Kite Connect](https://kite.trade/)
2. Create a new app to get your API Key and API Secret
3. Note down your API Key and API Secret

### 2. Configure Application

Edit `src/main/resources/application.yml`:

```yaml
kite:
  api-key: YOUR_API_KEY_HERE
  api-secret: YOUR_API_SECRET_HERE
```

Or set environment variables:
```bash
set KITE_API_KEY=your_api_key
set KITE_API_SECRET=your_api_secret
```

### 3. Build and Run

```bash
# Build the project
mvn clean install

# Run the application
mvn spring-boot:run
```

The application will start on `http://localhost:8080`

## API Documentation

Once the application is running, access the Swagger UI at:
```
http://localhost:8080/swagger-ui.html
```

## Authentication Flow

### 1. Get Login URL
```bash
GET http://localhost:8080/api/auth/login-url
```

### 2. Complete Login
- Open the login URL in a browser
- Login with your Zerodha credentials
- After successful login, you'll be redirected with a `request_token`

### 3. Generate Session
```bash
POST http://localhost:8080/api/auth/session
Content-Type: application/json

{
  "requestToken": "your_request_token_here"
}
```

This will return an access token that will be automatically used for subsequent requests.

## API Endpoints

### Authentication
- `GET /api/auth/login-url` - Get Kite Connect login URL
- `POST /api/auth/session` - Generate session with request token
- `GET /api/auth/profile` - Get user profile

### Orders
- `POST /api/orders` - Place a new order
- `GET /api/orders` - Get all orders for the day
- `PUT /api/orders/{orderId}` - Modify an existing order
- `DELETE /api/orders/{orderId}` - Cancel an order
- `GET /api/orders/{orderId}/history` - Get order history

### Portfolio
- `GET /api/portfolio/positions` - Get all positions
- `GET /api/portfolio/holdings` - Get all holdings
- `GET /api/portfolio/trades` - Get all trades for the day
- `PUT /api/portfolio/positions/convert` - Convert position product type

### Market Data
- `GET /api/market/quote` - Get quote for instruments
- `GET /api/market/ohlc` - Get OHLC data
- `GET /api/market/ltp` - Get Last Traded Price
- `GET /api/market/historical` - Get historical data
- `GET /api/market/instruments` - Get all instruments
- `GET /api/market/instruments/{exchange}` - Get instruments by exchange

### Account
- `GET /api/account/margins/{segment}` - Get account margins (equity/commodity)

### GTT Orders
- `GET /api/gtt` - Get all GTT orders
- `POST /api/gtt` - Place a GTT order
- `GET /api/gtt/{triggerId}` - Get GTT order by ID
- `PUT /api/gtt/{triggerId}` - Modify GTT order
- `DELETE /api/gtt/{triggerId}` - Delete GTT order

## Example: Place an Order

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

## Example: Get Market Quote

```bash
GET http://localhost:8080/api/market/quote?instruments=NSE:INFY&instruments=NSE:TCS
```

## Project Structure

```
src/
├── main/
│   ├── java/
│   │   └── com/
│   │       └── tradingbot/
│   │           ├── TradingBotApplication.java
│   │           ├── config/
│   │           │   ├── KiteConfig.java
│   │           │   └── SwaggerConfig.java
│   │           ├── controller/
│   │           │   ├── AuthController.java
│   │           │   ├── OrderController.java
│   │           │   ├── PortfolioController.java
│   │           │   ├── MarketDataController.java
│   │           │   ├── AccountController.java
│   │           │   └── GTTController.java
│   │           ├── service/
│   │           │   └── TradingService.java
│   │           ├── dto/
│   │           │   ├── OrderRequest.java
│   │           │   ├── OrderResponse.java
│   │           │   ├── LoginRequest.java
│   │           │   └── ApiResponse.java
│   │           └── exception/
│   │               └── GlobalExceptionHandler.java
│   └── resources/
│       └── application.yml
└── test/
    └── java/
```

## Key Features

### Order Management
- Support for all order types: MARKET, LIMIT, SL, SL-M
- Product types: CNC, MIS, NRML
- Order modification and cancellation
- Order history tracking

### Market Data
- Real-time quotes
- OHLC (Open, High, Low, Close) data
- Last Traded Price (LTP)
- Historical data with various intervals
- Instrument master data

### Portfolio Tracking
- Live positions (day and net)
- Holdings information
- Trade book
- Position conversion

### Advanced Features
- GTT (Good Till Triggered) orders
- Margin information
- Multiple exchange support (NSE, BSE, NFO, MCX, etc.)

## Security Considerations

1. **Never commit API credentials** to version control
2. Use environment variables for sensitive data
3. Implement proper authentication/authorization in production
4. Use HTTPS in production environments
5. Implement rate limiting to comply with Kite Connect API limits

## Rate Limits

Kite Connect API has rate limits:
- 3 requests per second per API key
- Plan your trading strategy accordingly

## Testing

The application can be tested using:
- Swagger UI (http://localhost:8080/swagger-ui.html)
- Postman or any REST client
- cURL commands

## Troubleshooting

### Common Issues

1. **Session Expired**: Generate a new session using the authentication flow
2. **Invalid API Key**: Check your API credentials in application.yml
3. **Order Placement Failed**: Verify order parameters and account margins
4. **Network Errors**: Check internet connectivity and Kite API status

## References

- [Kite Connect Documentation](https://kite.trade/docs/connect/v3/)
- [Kite Connect Java SDK](https://kite.trade/docs/javakiteconnect/v3/)
- [Spring Boot Documentation](https://docs.spring.io/spring-boot/docs/current/reference/html/)

## Support

For Kite Connect API issues:
- Email: kiteconnect@support.zerodha.com
- Forum: https://kite.trade/forum/

## License

This project is for educational purposes. Use at your own risk. Trading in financial markets involves risk.

## Disclaimer

This software is provided as-is for educational purposes. The authors are not responsible for any financial losses incurred through the use of this software. Always test thoroughly with small amounts before deploying any trading strategy.
package com.tradingbot.exception;

import com.tradingbot.dto.ApiResponse;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    
    @ExceptionHandler(KiteException.class)
    public ResponseEntity<ApiResponse<Object>> handleKiteException(KiteException e) {
        log.error("Kite API Exception: {}", e.message, e);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("Kite API Error: " + e.message));
    }
    
    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiResponse<Object>> handleIOException(IOException e) {
        log.error("IO Exception: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Network Error: " + e.getMessage()));
    }
    
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidationException(MethodArgumentNotValidException e) {
        String errorMessage = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + ", " + b)
                .orElse("Validation error");
        
        log.error("Validation Exception: {}", errorMessage);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("Validation Error: " + errorMessage));
    }
    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleGeneralException(Exception e) {
        log.error("Unexpected Exception: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Internal Server Error: " + e.getMessage()));
    }
}

