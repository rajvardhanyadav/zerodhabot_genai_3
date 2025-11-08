# Trading Strategies API Documentation

## Overview
This API allows frontend applications to execute automated trading strategies for NIFTY and BANKNIFTY options.

## Base URL
```
http://localhost:8080/api/strategies
```

## Available Strategies

### 1. ATM Straddle
- **Type**: `ATM_STRADDLE`
- **Description**: Buy 1 ATM Call + Buy 1 ATM Put
- **Use Case**: Non-directional strategy, profits from high volatility
- **Risk**: Limited to premium paid
- **Status**: ✅ Implemented

### 2. ATM Strangle
- **Type**: `ATM_STRANGLE`
- **Description**: Buy 1 OTM Call + Buy 1 OTM Put
- **Use Case**: Lower cost alternative to straddle
- **Risk**: Limited to premium paid
- **Status**: ✅ Implemented

## API Endpoints

### 1. Execute Strategy
Execute a trading strategy based on user selection.

**Endpoint**: `POST /api/strategies/execute`

**Request Body**:
```json
{
  "strategyType": "ATM_STRADDLE",
  "instrumentType": "NIFTY",
  "expiry": "WEEKLY",
  "quantity": 50,
  "orderType": "MARKET",
  "strikeGap": 100,
  "autoSquareOff": false,
  "stopLoss": 50.0,
  "target": 100.0
}
```

**Request Fields**:
| Field | Type | Required | Description | Example |
|-------|------|----------|-------------|---------|
| strategyType | String | Yes | Type of strategy | ATM_STRADDLE, ATM_STRANGLE |
| instrumentType | String | Yes | Index to trade | NIFTY, BANKNIFTY, FINNIFTY |
| expiry | String | Yes | Option expiry | WEEKLY, MONTHLY, or yyyy-MM-dd |
| quantity | Integer | No | Lot quantity (default: 1 lot) | 50 |
| orderType | String | No | Order type (default: MARKET) | MARKET, LIMIT |
| strikeGap | Double | No | Strike gap for strangle (auto-calculated) | 100.0 |
| autoSquareOff | Boolean | No | Auto exit at 3:15 PM | true, false |
| stopLoss | Double | No | Stop loss percentage | 50.0 |
| target | Double | No | Target profit percentage | 100.0 |

**Success Response** (200 OK):
```json
{
  "success": true,
  "message": "Strategy executed successfully",
  "data": {
    "executionId": "abc123-def456-ghi789",
    "status": "COMPLETED",
    "message": "ATM Straddle executed successfully",
    "orders": [
      {
        "orderId": "221108000123456",
        "tradingSymbol": "NIFTY2511024000CE",
        "optionType": "CE",
        "strike": 24000.0,
        "quantity": 50,
        "price": 120.50,
        "status": "COMPLETED"
      },
      {
        "orderId": "221108000123457",
        "tradingSymbol": "NIFTY2511024000PE",
        "optionType": "PE",
        "strike": 24000.0,
        "quantity": 50,
        "price": 115.75,
        "status": "COMPLETED"
      }
    ],
    "totalPremium": 11812.50,
    "currentValue": 11812.50,
    "profitLoss": 0.0,
    "profitLossPercentage": 0.0
  }
}
```

**Error Response** (400 Bad Request):
```json
{
  "success": false,
  "message": "Strategy execution failed: Insufficient funds",
  "data": null
}
```

---

### 2. Get Active Strategies
Retrieve all currently active strategy executions.

**Endpoint**: `GET /api/strategies/active`

**Response**:
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
      "status": "COMPLETED",
      "message": "Strategy executed successfully",
      "entryPrice": 11812.50,
      "currentPrice": 12500.00,
      "profitLoss": 687.50,
      "timestamp": 1699430400000
    }
  ]
}
```

---

### 3. Get Strategy Details
Get details of a specific strategy execution.

**Endpoint**: `GET /api/strategies/{executionId}`

**Path Parameters**:
- `executionId`: Unique execution identifier

**Response**: Same as individual strategy object from active strategies list.

---

### 4. Get Available Strategy Types
Get list of all available strategy types.

**Endpoint**: `GET /api/strategies/types`

**Response**:
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
    },
    {
      "name": "BULL_CALL_SPREAD",
      "description": "Bullish strategy using call options",
      "implemented": false
    }
  ]
}
```

---

### 5. Get Available Instruments
Get list of tradeable instruments with details.

**Endpoint**: `GET /api/strategies/instruments`

**Response**:
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
      "lotSize": 15,
      "strikeInterval": 100.0
    },
    {
      "code": "FINNIFTY",
      "name": "FIN NIFTY",
      "lotSize": 40,
      "strikeInterval": 50.0
    }
  ]
}
```

---

### 6. Get Available Expiries
Get available expiry dates for an instrument.

**Endpoint**: `GET /api/strategies/expiries/{instrumentType}`

**Path Parameters**:
- `instrumentType`: Instrument code (NIFTY, BANKNIFTY, FINNIFTY)

**Response**:
```json
{
  "success": true,
  "message": "Success",
  "data": ["WEEKLY", "MONTHLY"]
}
```

---

## Frontend Integration Examples

### React/JavaScript Example

```javascript
// Execute ATM Straddle Strategy
const executeStrategy = async () => {
  try {
    const response = await fetch('http://localhost:8080/api/strategies/execute', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        strategyType: 'ATM_STRADDLE',
        instrumentType: 'NIFTY',
        expiry: 'WEEKLY',
        quantity: 50,
        orderType: 'MARKET'
      })
    });
    
    const result = await response.json();
    
    if (result.success) {
      console.log('Strategy executed:', result.data);
      alert(`Strategy executed! Execution ID: ${result.data.executionId}`);
    } else {
      console.error('Error:', result.message);
      alert(`Error: ${result.message}`);
    }
  } catch (error) {
    console.error('Network error:', error);
  }
};

// Get active strategies
const getActiveStrategies = async () => {
  try {
    const response = await fetch('http://localhost:8080/api/strategies/active');
    const result = await response.json();
    
    if (result.success) {
      return result.data;
    }
  } catch (error) {
    console.error('Error fetching strategies:', error);
  }
};

// Get available instruments
const getInstruments = async () => {
  try {
    const response = await fetch('http://localhost:8080/api/strategies/instruments');
    const result = await response.json();
    
    if (result.success) {
      return result.data;
    }
  } catch (error) {
    console.error('Error fetching instruments:', error);
  }
};
```

### Angular/TypeScript Example

```typescript
import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class StrategyService {
  private apiUrl = 'http://localhost:8080/api/strategies';

  constructor(private http: HttpClient) {}

  executeStrategy(request: StrategyRequest): Observable<ApiResponse> {
    return this.http.post<ApiResponse>(`${this.apiUrl}/execute`, request);
  }

  getActiveStrategies(): Observable<ApiResponse> {
    return this.http.get<ApiResponse>(`${this.apiUrl}/active`);
  }

  getInstruments(): Observable<ApiResponse> {
    return this.http.get<ApiResponse>(`${this.apiUrl}/instruments`);
  }

  getStrategyTypes(): Observable<ApiResponse> {
    return this.http.get<ApiResponse>(`${this.apiUrl}/types`);
  }
}

interface StrategyRequest {
  strategyType: string;
  instrumentType: string;
  expiry: string;
  quantity?: number;
  orderType?: string;
  strikeGap?: number;
}

interface ApiResponse {
  success: boolean;
  message: string;
  data: any;
}
```

---

## Dashboard UI Components Needed

### 1. Strategy Selection Form
- Dropdown: Strategy Type (ATM_STRADDLE, ATM_STRANGLE)
- Dropdown: Instrument (NIFTY, BANKNIFTY, FINNIFTY)
- Dropdown: Expiry (WEEKLY, MONTHLY)
- Input: Quantity (optional, defaults to 1 lot)
- Button: Execute Strategy

### 2. Active Strategies Table
- Columns: Execution ID, Strategy Type, Instrument, Status, Entry Price, Current P&L
- Actions: View Details, Square Off

### 3. Strategy Details Modal
- Show individual order details
- Real-time P&L updates
- Option to exit strategy

---

## Important Notes

1. **Authentication**: All API calls require valid Kite session token (set via `/api/auth/session`)

2. **Market Hours**: Strategies can only be executed during market hours (9:15 AM - 3:30 PM IST)

3. **Lot Sizes**:
   - NIFTY: 50 shares per lot
   - BANKNIFTY: 15 shares per lot
   - FINNIFTY: 40 shares per lot

4. **Strike Intervals**:
   - NIFTY: 50 points
   - BANKNIFTY: 100 points
   - FINNIFTY: 50 points

5. **Default Strike Gaps (for Strangle)**:
   - NIFTY: 100 points
   - BANKNIFTY: 200 points

6. **Error Handling**: Always check the `success` field in responses

---

## Testing with Swagger

Access Swagger UI at: `http://localhost:8080/swagger-ui.html`

Navigate to "Trading Strategies" section to test all endpoints interactively.

---

## Status Codes

| Status | Description |
|--------|-------------|
| PENDING | Strategy is queued for execution |
| EXECUTING | Strategy is being executed |
| COMPLETED | Strategy executed successfully |
| FAILED | Strategy execution failed |

---

## Next Steps for Frontend Developers

1. Create a dashboard page with strategy selection form
2. Implement real-time strategy monitoring
3. Add P&L calculation and display
4. Implement strategy exit/square-off functionality
5. Add notifications for strategy status updates
6. Create charts for strategy performance visualization

---

## Support

For issues or questions, refer to the main README.md or check Swagger documentation.

