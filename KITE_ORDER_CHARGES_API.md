# Kite Order Charges API - Correct Implementation

## Overview

The **Order Charges API** (`/oms/charges/orders`) from Kite Zerodha calculates the **actual charges applied to orders that have already been placed and executed** during the trading day. This is NOT for estimating charges before placing orders - it's for getting the real charge breakdown after orders are complete.

---

## What This API Does

- **Fetches actual charges** for executed orders placed today
- **Shows detailed breakdown** of all costs: brokerage, STT, exchange charges, GST, SEBI charges, stamp duty
- **Helps track daily costs** and P&L accurately
- **Provides transparency** on what you've actually paid

---

## API Endpoint

### Get Today's Order Charges

**Endpoint:** `GET /api/orders/charges`

**Description:** Fetches detailed charge breakdown from Kite API for all completed orders placed today.

**Request:** No body required - automatically fetches all executed orders from today

**Response:**
```json
{
  "success": true,
  "message": "Order charges fetched successfully",
  "data": [
    {
      "transactionType": "BUY",
      "tradingsymbol": "NIFTY25NOV25850PE",
      "exchange": "NFO",
      "variety": "regular",
      "product": "MIS",
      "orderType": "MARKET",
      "quantity": 75,
      "price": 164.65,
      "charges": {
        "transactionTax": 0.0,
        "transactionTaxType": "stt",
        "exchangeTurnoverCharge": 4.387510875,
        "sebiTurnoverCharge": 0.012348749999999999,
        "brokerage": 20.0,
        "stampDuty": 0.0,
        "gst": {
          "igst": 4.3919747325,
          "cgst": 0.0,
          "sgst": 0.0,
          "total": 4.3919747325
        },
        "total": 28.7918343575
      }
    },
    {
      "transactionType": "SELL",
      "tradingsymbol": "NIFTY25NOV25850PE",
      "exchange": "NFO",
      "variety": "regular",
      "product": "MIS",
      "orderType": "MARKET",
      "quantity": 75,
      "price": 170.05,
      "charges": {
        "transactionTax": 12.75375,
        "transactionTaxType": "stt",
        "exchangeTurnoverCharge": 4.531407375,
        "sebiTurnoverCharge": 0.01275375,
        "brokerage": 20.0,
        "stampDuty": 0.0,
        "gst": {
          "igst": 4.4179490025,
          "cgst": 0.0,
          "sgst": 0.0,
          "total": 4.4179490025
        },
        "total": 41.71586012750001
      }
    }
  ]
}
```

---

## How It Works

### Backend Flow

1. **Fetch Today's Orders**: The service calls `kiteConnect.getOrders()` to get all orders placed today
2. **Filter Executed Orders**: Only orders with status "COMPLETE" are included
3. **Build Request**: Creates a JSON array with order details (order_id, exchange, tradingsymbol, etc.)
4. **Call Kite API**: Makes HTTP POST to `https://kite.zerodha.com/oms/charges/orders`
5. **Parse Response**: Converts Kite's response into structured Java objects
6. **Return Data**: Returns detailed charge breakdown for each executed order

### Authentication

The API call to Kite uses:
- **Authorization Header**: `token {api_key}:{access_token}`
- **Content-Type**: `application/json`
- **X-Kite-Version**: `3`

---

## Response Fields Explained

### Order Details
| Field | Description |
|-------|-------------|
| `transactionType` | BUY or SELL |
| `tradingsymbol` | Trading symbol (e.g., NIFTY25NOV25850PE) |
| `exchange` | Exchange (NFO, NSE, BSE, etc.) |
| `variety` | Order variety (regular, amo, co, iceberg) |
| `product` | Product type (MIS, NRML, CNC) |
| `orderType` | MARKET or LIMIT |
| `quantity` | Number of units filled |
| `price` | Average execution price |

### Charges Breakdown
| Field | Description | Example |
|-------|-------------|---------|
| `transactionTax` | STT (Securities Transaction Tax) | 12.75 |
| `transactionTaxType` | Type of tax (always "stt") | "stt" |
| `exchangeTurnoverCharge` | Exchange transaction charges | 4.53 |
| `sebiTurnoverCharge` | SEBI regulatory charges | 0.01 |
| `brokerage` | Brokerage charged | 20.00 |
| `stampDuty` | Stamp duty (on buy orders) | 0.00 |
| `gst.igst` | Integrated GST (18% on brokerage + exchange charges) | 4.42 |
| `gst.cgst` | Central GST | 0.00 |
| `gst.sgst` | State GST | 0.00 |
| `gst.total` | Total GST | 4.42 |
| `total` | **Total charges for this order** | 41.72 |

---

## Usage Examples

### Example 1: Get Today's Charges via cURL

```bash
curl -X GET http://localhost:8080/api/orders/charges \
  -H "Content-Type: application/json"
```

### Example 2: Calculate Day's Total Charges

```java
List<OrderChargesResponse> charges = tradingService.getOrderCharges();

double totalCharges = charges.stream()
    .mapToDouble(c -> c.getCharges().getTotal())
    .sum();

System.out.println("Total charges for today: ₹" + totalCharges);
```

### Example 3: Get Brokerage and Tax Breakdown

```java
List<OrderChargesResponse> charges = tradingService.getOrderCharges();

double totalBrokerage = 0.0;
double totalSTT = 0.0;
double totalGST = 0.0;

for (OrderChargesResponse charge : charges) {
    totalBrokerage += charge.getCharges().getBrokerage();
    totalSTT += charge.getCharges().getTransactionTax();
    totalGST += charge.getCharges().getGst().getTotal();
}

System.out.println("Brokerage: ₹" + totalBrokerage);
System.out.println("STT: ₹" + totalSTT);
System.out.println("GST: ₹" + totalGST);
```

---

## Integration with Your Trading Bot

### 1. Daily P&L Calculation

```java
// Get today's charges
List<OrderChargesResponse> charges = tradingService.getOrderCharges();
double totalCharges = charges.stream()
    .mapToDouble(c -> c.getCharges().getTotal())
    .sum();

// Calculate actual P&L
double grossPnL = calculateGrossPnL();
double netPnL = grossPnL - totalCharges;
```

### 2. Per-Strategy Cost Tracking

```java
// Filter charges by trading symbol
List<OrderChargesResponse> niftyCharges = charges.stream()
    .filter(c -> c.getTradingsymbol().startsWith("NIFTY"))
    .collect(Collectors.toList());

double niftyTotalCost = niftyCharges.stream()
    .mapToDouble(c -> c.getCharges().getTotal())
    .sum();
```

### 3. Monitor High-Cost Trades

```java
// Find orders with charges > ₹50
List<OrderChargesResponse> highCostTrades = charges.stream()
    .filter(c -> c.getCharges().getTotal() > 50.0)
    .collect(Collectors.toList());

highCostTrades.forEach(trade -> {
    System.out.println(trade.getTradingsymbol() + ": ₹" + 
                      trade.getCharges().getTotal());
});
```

---

## Important Notes

### Charge Components

1. **Brokerage**: Zerodha charges ₹20 per executed order (flat fee for intraday and F&O)
2. **STT**: Only on SELL orders for options (0.0125% of turnover)
3. **Exchange Charges**: Varies by exchange (NSE/BSE/NFO)
4. **GST**: 18% on brokerage + transaction charges
5. **SEBI Charges**: ₹10 per crore turnover
6. **Stamp Duty**: On BUY orders only (varies by state)

### When Charges Are Applied

- Charges are calculated only for **COMPLETE** orders
- Rejected, cancelled, or pending orders don't incur charges
- The API returns data for the **current trading day** only

### API Limitations

- Only works during market hours (9:15 AM - 3:30 PM IST)
- Requires valid access token
- Only shows executed orders, not pending ones
- Data is for current day only (not historical)

---

## Error Handling

### Common Errors

1. **No Orders Found**
   ```json
   {
     "success": true,
     "message": "Order charges fetched successfully",
     "data": []
   }
   ```

2. **No Executed Orders**
   ```json
   {
     "success": true,
     "message": "Order charges fetched successfully",
     "data": []
   }
   ```

3. **Authentication Error**
   ```json
   {
     "success": false,
     "message": "Failed to fetch order charges: Unauthorized",
     "data": null
   }
   ```

---

## Implementation Details

### Files Created/Modified

1. **DTOs**:
   - `OrderChargesRequest.java` - Internal use (not exposed in API)
   - `OrderChargesResponse.java` - API response structure

2. **Service**:
   - `TradingService.getOrderCharges()` - Calls Kite API and parses response

3. **Controller**:
   - `OrderController.getOrderCharges()` - GET /api/orders/charges endpoint

4. **Config**:
   - `KiteConfig.restTemplate()` - Added RestTemplate bean for HTTP calls

### Key Features

- ✅ Direct HTTP call to Kite's charges endpoint
- ✅ Automatic filtering of executed orders
- ✅ Proper error handling
- ✅ Clean JSON response structure
- ✅ Swagger documentation included

---

## Testing

### Test Endpoint

```bash
# Start the application
mvn spring-boot:run

# Call the endpoint
curl http://localhost:8080/api/orders/charges
```

### Check Swagger UI

Navigate to: `http://localhost:8080/swagger-ui.html`
- Go to **Orders** section
- Find **GET /api/orders/charges**
- Click "Try it out" → "Execute"

---

## Comparison: Before vs After

### ❌ Previous (Incorrect) Implementation
- Tried to estimate charges before placing orders
- Used hardcoded charge calculations
- Required user to input order details
- Not based on actual Kite data

### ✅ Current (Correct) Implementation
- Gets **actual charges** from Kite API
- For **already executed** orders
- Automatic - no input needed
- Shows real costs incurred today
- Matches Kite's official charge structure

---

## Use Cases

### 1. End-of-Day Reporting
```java
// Generate daily report
List<OrderChargesResponse> charges = tradingService.getOrderCharges();
generateDailyReport(charges);
```

### 2. Cost Analysis
```java
// Analyze which products cost more
Map<String, Double> costByProduct = charges.stream()
    .collect(Collectors.groupingBy(
        OrderChargesResponse::getProduct,
        Collectors.summingDouble(c -> c.getCharges().getTotal())
    ));
```

### 3. Tax Reporting
```java
// Calculate total STT paid
double totalSTT = charges.stream()
    .mapToDouble(c -> c.getCharges().getTransactionTax())
    .sum();
```

---

## Next Steps

1. **Add to Dashboard**: Display today's total charges on your trading dashboard
2. **Daily Report**: Email daily charge summary at market close
3. **Cost Alerts**: Alert if charges exceed a threshold
4. **Historical Tracking**: Store charges data for monthly/yearly analysis
5. **Strategy Optimization**: Use charge data to optimize strategy profitability

---

**Last Updated:** November 12, 2025  
**Version:** 2.0.0 (Corrected Implementation)

