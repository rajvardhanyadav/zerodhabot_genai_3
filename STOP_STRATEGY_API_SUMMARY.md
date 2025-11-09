# Stop Strategy APIs - Implementation Summary

## Date: November 9, 2025

## Overview
Added two new API endpoints to allow stopping active strategies by closing all legs at market price. This enables manual exit from active positions.

## New API Endpoints

### 1. Stop Strategy by ID
**Endpoint**: `DELETE /api/strategies/stop/{executionId}`

**Purpose**: Stop a specific active strategy by closing all legs at market price

**Features**:
- Validates strategy exists and is in ACTIVE status
- Closes all legs (CE and PE) at market price
- Returns detailed exit order information
- Updates strategy status to COMPLETED
- Supports both Paper Trading and Live Trading modes

**Use Case**: User wants to manually exit a specific running strategy before SL/Target is hit

---

### 2. Stop All Active Strategies
**Endpoint**: `DELETE /api/strategies/stop-all`

**Purpose**: Stop all currently active strategies in one operation

**Features**:
- Finds all strategies with ACTIVE status
- Closes all legs for each strategy at market price
- Returns summary with total legs closed and individual strategy results
- Gracefully handles errors for individual strategies
- Supports both Paper Trading and Live Trading modes

**Use Case**: End of day square-off or emergency exit from all positions

---

## Technical Implementation

### Files Modified

1. **StrategyExecution.java** (Model)
   - Added `orderLegs` field to track trading symbols and quantities
   - Added `OrderLeg` nested class to store leg details

2. **StrategyService.java** (Service Layer)
   - Added `updateOrderLegs()` - Store order details after strategy execution
   - Added `stopStrategy()` - Stop single strategy by ID
   - Added `stopAllActiveStrategies()` - Stop all active strategies
   - Modified `executeStrategy()` - Call updateOrderLegs for ACTIVE strategies

3. **StrategyController.java** (API Layer)
   - Added `DELETE /api/strategies/stop/{executionId}` endpoint
   - Added `DELETE /api/strategies/stop-all` endpoint

4. **STRATEGY_API_DOCS.md** (Documentation)
   - Added complete documentation for both new endpoints
   - Added JavaScript/TypeScript integration examples
   - Added success/error response examples

---

## Response Format

### Stop Single Strategy Response
```json
{
  "success": true,
  "message": "Strategy stopped successfully",
  "data": {
    "executionId": "abc123-def456-ghi789",
    "totalLegs": 2,
    "successCount": 2,
    "failureCount": 0,
    "status": "SUCCESS",
    "exitOrders": [
      {
        "tradingSymbol": "NIFTY2511024000CE",
        "optionType": "CE",
        "quantity": "50",
        "exitOrderId": "221108000789012",
        "status": "SUCCESS",
        "message": "Order placed successfully"
      },
      {
        "tradingSymbol": "NIFTY2511024000PE",
        "optionType": "PE",
        "quantity": "50",
        "exitOrderId": "221108000789013",
        "status": "SUCCESS",
        "message": "Order placed successfully"
      }
    ]
  }
}
```

### Stop All Strategies Response
```json
{
  "success": true,
  "message": "All active strategies stopped",
  "data": {
    "message": "Stopped 3 strategies",
    "totalStrategies": 3,
    "totalLegsClosedSuccessfully": 6,
    "totalLegsFailed": 0,
    "results": [...]
  }
}
```

---

## Error Handling

### Possible Error Scenarios

1. **Strategy Not Found**
   - HTTP 400: "Strategy not found: {executionId}"

2. **Invalid Status**
   - HTTP 400: "Strategy is not active. Current status: COMPLETED"

3. **No Order Legs**
   - HTTP 400: "No order legs found for strategy: {executionId}"

4. **Order Placement Failure**
   - Continues with other legs, reports in failureCount
   - Status becomes "PARTIAL" if some legs fail

---

## Frontend Integration

### JavaScript Example
```javascript
// Stop a specific strategy
const stopStrategy = async (executionId) => {
  const response = await fetch(
    `http://localhost:8080/api/strategies/stop/${executionId}`, 
    { method: 'DELETE' }
  );
  const result = await response.json();
  
  if (result.success) {
    alert(`Strategy stopped! ${result.data.successCount} legs closed.`);
  }
};

// Stop all strategies
const stopAllStrategies = async () => {
  const response = await fetch(
    'http://localhost:8080/api/strategies/stop-all', 
    { method: 'DELETE' }
  );
  const result = await response.json();
  
  if (result.success) {
    alert(`${result.data.message}\nLegs closed: ${result.data.totalLegsClosedSuccessfully}`);
  }
};
```

---

## Testing

### Manual Testing Steps

1. **Execute a Strategy**
   ```bash
   POST /api/strategies/execute
   {
     "strategyType": "ATM_STRADDLE",
     "instrumentType": "NIFTY",
     "expiry": "WEEKLY"
   }
   ```
   Note the returned `executionId`

2. **Verify Strategy is Active**
   ```bash
   GET /api/strategies/active
   ```

3. **Stop the Strategy**
   ```bash
   DELETE /api/strategies/stop/{executionId}
   ```

4. **Verify Strategy is Completed**
   ```bash
   GET /api/strategies/{executionId}
   ```
   Status should be "COMPLETED"

### Test Stop All
1. Execute 2-3 strategies
2. Call `DELETE /api/strategies/stop-all`
3. Verify all strategies are marked as COMPLETED

---

## Key Features

✅ **Paper Trading Support**: Works in both paper and live trading modes
✅ **Error Recovery**: Continues closing other legs even if one fails
✅ **Detailed Reporting**: Returns complete exit order details
✅ **Status Validation**: Only stops ACTIVE strategies
✅ **Market Orders**: Uses MARKET order type for quick execution
✅ **Logging**: Comprehensive logging for debugging
✅ **Thread Safe**: Uses ConcurrentHashMap for strategy storage

---

## Future Enhancements (Optional)

1. **Partial Exit**: Allow closing only one leg (CE or PE)
2. **Custom Exit Price**: Support LIMIT orders with user-defined price
3. **Schedule Square-Off**: Auto-square off at specified time (e.g., 3:15 PM)
4. **Exit Confirmation**: Add confirmation dialog in frontend
5. **Exit Reasons**: Track why strategy was stopped (manual, SL, target, time)
6. **P&L Calculation**: Calculate and return realized P&L on exit

---

## Notes for Frontend Developers

1. Add a "Stop" button next to each active strategy in the dashboard
2. Add a "Stop All" button at the top of active strategies table
3. Show confirmation dialog before stopping strategies
4. Display exit order details in a modal after stopping
5. Refresh active strategies list after stopping
6. Show success/error toast notifications
7. Disable stop button for non-ACTIVE strategies

---

## Production Considerations

1. **Rate Limiting**: Consider adding rate limits to prevent API abuse
2. **Audit Trail**: Log all stop requests for compliance
3. **Notifications**: Send email/SMS when strategies are stopped
4. **Risk Management**: Add risk checks before allowing mass stop-all
5. **Rollback**: Consider adding undo functionality for accidental stops

---

## API Summary Table

| Endpoint | Method | Purpose | Paper Trading |
|----------|--------|---------|---------------|
| `/api/strategies/stop/{id}` | DELETE | Stop specific strategy | ✅ Yes |
| `/api/strategies/stop-all` | DELETE | Stop all active strategies | ✅ Yes |

---

## Conclusion

The stop strategy APIs provide essential functionality for manual position management. They integrate seamlessly with the existing strategy execution framework and support both paper and live trading modes.

For complete API documentation, see: **STRATEGY_API_DOCS.md**

