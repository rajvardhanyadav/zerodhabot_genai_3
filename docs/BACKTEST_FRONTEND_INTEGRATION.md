# Backtest API — Frontend Integration Guide

> **Purpose:** This document provides everything a frontend developer (or frontend IDE copilot) needs to integrate with the Backtest API. It is generated from the actual Java implementation and is the source of truth.

---

## Table of Contents

1. [Global API Conventions](#1-global-api-conventions)
2. [Endpoint Reference](#2-endpoint-reference)
3. [Type Definitions (TypeScript)](#3-type-definitions-typescript)
4. [Request/Response Examples](#4-requestresponse-examples)
5. [Exit Reason Codes](#5-exit-reason-codes)
6. [Validation Rules & Error Handling](#6-validation-rules--error-handling)
7. [Async Polling Pattern](#7-async-polling-pattern)
8. [Recommended UI Components](#8-recommended-ui-components)
9. [API Client Reference Implementation](#9-api-client-reference-implementation)
10. [Important Business Logic Notes](#10-important-business-logic-notes)

---

## 1. Global API Conventions

### Base URL

```
{BACKEND_URL}/api/backtest
```

- **Local development:** `http://localhost:8080/api/backtest`
- **Cloud Run production:** `https://<service-url>/api/backtest`

### Headers

| Header | Required | Description |
|--------|----------|-------------|
| `Content-Type` | Yes (POST) | `application/json` |
| `X-User-Id` | Recommended | User context for multi-user isolation. The backend `UserContextFilter` extracts this. If omitted, the backend may derive it from the active Kite session. |

### Response Envelope

Every response uses this wrapper:

```json
{
  "success": true | false,
  "message": "Human-readable message",
  "data": <payload or null>
}
```

### HTTP Status Codes

| Code | Meaning |
|------|---------|
| `200` | Success (even for FAILED backtests — check `data.status`) |
| `400` | Validation error or `BacktestException` (check `message`) |
| `404` | Backtest result not found (for `/result/{id}`) |

### CORS

The backend allows all origins (`*`), all methods, and all headers. No special CORS configuration is needed on the frontend.

### Swagger UI

Interactive API docs available at: `{BACKEND_URL}/swagger-ui.html`

---

## 2. Endpoint Reference

### 2.1 Run Single-Day Backtest

**`POST /api/backtest/run`**

Runs a full-day strategy simulation synchronously. The response contains the complete result with all trades and metrics.

**Typical latency:** 2–5 seconds (3 Kite API calls with 350ms rate-limit delay each).

| Aspect | Detail |
|--------|--------|
| Method | `POST` |
| Content-Type | `application/json` |
| Request Body | `BacktestRequest` |
| Response Body | `ApiResponse<BacktestResult>` |

---

### 2.2 Run Batch Backtest (Date Range)

**`POST /api/backtest/batch?fromDate={date}&toDate={date}`**

Runs backtests for each trading day in a date range. Weekends are automatically skipped.

| Aspect | Detail |
|--------|--------|
| Method | `POST` |
| Query Params | `fromDate` (yyyy-MM-dd), `toDate` (yyyy-MM-dd) |
| Request Body | `BacktestRequest` (used as template; `backtestDate` is ignored — overridden by each day in range) |
| Response Body | `ApiResponse<BacktestResult[]>` |

**Warning:** Batch requests can take a long time. For a 20-day range, expect ~60–100 seconds. Consider using the async endpoint for ranges > 5 days.

---

### 2.3 Run Async Backtest

**`POST /api/backtest/run-async`**

Starts a backtest in a background thread. Returns immediately with a `backtestId` that can be polled.

| Aspect | Detail |
|--------|--------|
| Method | `POST` |
| Request Body | `BacktestRequest` |
| Response Body | `ApiResponse<string>` (the `data` field is the `backtestId`) |

---

### 2.4 Get Backtest Result by ID

**`GET /api/backtest/result/{backtestId}`**

Retrieves a backtest result from the in-memory cache.

| Aspect | Detail |
|--------|--------|
| Method | `GET` |
| Path Param | `backtestId` (UUID string) |
| Response `200` | `ApiResponse<BacktestResult>` |
| Response `404` | Empty body (result not in cache or expired) |

---

### 2.5 Get All Cached Results

**`GET /api/backtest/results`**

Returns all backtest results currently in the in-memory cache (max 100 entries).

| Aspect | Detail |
|--------|--------|
| Method | `GET` |
| Response Body | `ApiResponse<BacktestResult[]>` |

---

### 2.6 Get Supported Strategies

**`GET /api/backtest/strategies`**

Returns the list of strategy types and whether they support backtesting.

| Aspect | Detail |
|--------|--------|
| Method | `GET` |
| Response Body | `ApiResponse<StrategyInfo[]>` |

---

### 2.7 Clear Cache

**`DELETE /api/backtest/cache`**

Removes all cached backtest results.

| Aspect | Detail |
|--------|--------|
| Method | `DELETE` |
| Response Body | `ApiResponse<null>` |

---

## 3. Type Definitions (TypeScript)

Copy these into your frontend project for type safety.

```typescript
// ============================================================
// API Envelope
// ============================================================
interface ApiResponse<T> {
  success: boolean;
  message: string;
  data: T | null;
}

// ============================================================
// BacktestRequest — POST body for /run, /run-async, /batch
// ============================================================
interface BacktestRequest {
  // ---- REQUIRED FIELDS ----
  /** Trading day to simulate (yyyy-MM-dd). Must be today or earlier, not a weekend. */
  backtestDate: string;

  /** Strategy type. Currently only SELL_ATM_STRADDLE is supported for backtest. */
  strategyType: "SELL_ATM_STRADDLE" | "ATM_STRADDLE";

  /** Underlying index. */
  instrumentType: "NIFTY" | "BANKNIFTY";

  /** Options expiry date in yyyy-MM-dd format. Must match an active expiry in Kite's instrument dump. */
  expiryDate: string;

  // ---- OPTIONAL FIELDS (with defaults) ----

  /** Number of lots to simulate. Default: 1. */
  lots?: number;

  /**
   * SL/Target exit mode.
   * - "points" → uses stopLossPoints / targetPoints (fixed-point MTM)
   * - "premium" or "percentage" → uses targetDecayPct / stopLossExpansionPct (premium-based)
   * Default: "points"
   */
  slTargetMode?: "points" | "premium" | "percentage";

  /** Stop loss in points (used when slTargetMode = "points"). Default: 2.0 */
  stopLossPoints?: number;

  /** Target in points (used when slTargetMode = "points"). Default: 2.0 */
  targetPoints?: number;

  /** Target decay % (used when slTargetMode = "premium"). Default: 3.5 */
  targetDecayPct?: number;

  /** SL expansion % (used when slTargetMode = "premium"). Default: 7.0 */
  stopLossExpansionPct?: number;

  /** Simulation start time (HH:mm). Default: "09:20" */
  startTime?: string;

  /** Simulation end time (HH:mm). Default: "15:30" */
  endTime?: string;

  /** Auto square-off time — no new entries after this, forced exit at this time (HH:mm). Default: "15:10" */
  autoSquareOffTime?: string;

  /** Kite candle interval. Default: "minute". Options: "minute", "5minute", "15minute", "day" */
  candleInterval?: string;

  /** Enable auto-restart at next 5-min boundary after SL/target hit. Default: true */
  autoRestartEnabled?: boolean;

  /** Max auto-restarts per day. 0 = unlimited. Default: 0 */
  maxAutoRestarts?: number;

  /** Enable trailing stop loss. Default: false */
  trailingStopEnabled?: boolean;

  /** Trailing stop activation threshold in points. */
  trailingActivationPoints?: number;

  /** Trailing stop trail distance in points. */
  trailingDistancePoints?: number;
}

// ============================================================
// BacktestResult — returned by all run/result endpoints
// ============================================================
interface BacktestResult {
  /** Unique identifier (UUID) */
  backtestId: string;

  /** The date that was backtested (yyyy-MM-dd) */
  backtestDate: string;

  /** Strategy name, e.g. "SELL_ATM_STRADDLE" */
  strategyType: string;

  /** "NIFTY" or "BANKNIFTY" */
  instrumentType: string;

  /** Execution status */
  status: "COMPLETED" | "FAILED" | "RUNNING";

  /** Error message (only when status = "FAILED") */
  errorMessage: string | null;

  // ---- Market Data ----

  /** Index spot price at 9:15 AM on the backtest date */
  spotPriceAtEntry: number;

  /** ATM strike selected (rounded to nearest strike interval) */
  atmStrike: number;

  // ---- Trade List ----

  /** All simulated entry→exit cycles for the day */
  trades: BacktestTrade[];

  // ---- Aggregate Metrics ----

  /** Sum of all trades' pnlPoints */
  totalPnLPoints: number;

  /** Sum of all trades' pnlAmount (INR) */
  totalPnLAmount: number;

  /** Total number of trade cycles */
  totalTrades: number;

  /** Trades with pnlAmount >= 0 */
  winningTrades: number;

  /** Trades with pnlAmount < 0 */
  losingTrades: number;

  /** Win percentage (0–100, 2 decimal places) */
  winRate: number;

  /** Max peak-to-trough drawdown as percentage (0–100) */
  maxDrawdownPct: number;

  /** Max running profit as % of first trade's notional (0–100) */
  maxProfitPct: number;

  /** Average winning trade amount (INR) */
  avgWinAmount: number;

  /** Average losing trade amount (INR, positive number) */
  avgLossAmount: number;

  /** Gross profit / Gross loss. 999.99 if no losses. */
  profitFactor: number;

  /** Number of auto-restart cycles that occurred */
  restartCount: number;

  /** Backend execution time in milliseconds */
  executionDurationMs: number;
}

// ============================================================
// BacktestTrade — one entry→exit cycle (covers both CE + PE legs)
// ============================================================
interface BacktestTrade {
  /** Sequential trade number (1, 2, 3...) */
  tradeNumber: number;

  /** CE option trading symbol, e.g. "NIFTY2510924500CE" */
  ceSymbol: string;

  /** PE option trading symbol, e.g. "NIFTY2510924500PE" */
  peSymbol: string;

  /** ATM strike price */
  strikePrice: number;

  /** Entry timestamp (ISO 8601 local datetime: "2025-01-15T09:20:00") */
  entryTime: string;

  /** CE leg entry price */
  ceEntryPrice: number;

  /** PE leg entry price */
  peEntryPrice: number;

  /** ceEntryPrice + peEntryPrice */
  combinedEntryPremium: number;

  /** Exit timestamp (ISO 8601 local datetime) */
  exitTime: string;

  /** CE leg exit price */
  ceExitPrice: number;

  /** PE leg exit price */
  peExitPrice: number;

  /** ceExitPrice + peExitPrice */
  combinedExitPremium: number;

  /** Quantity per leg (lots * lot size) */
  quantity: number;

  /** P&L in points: combinedEntryPremium - combinedExitPremium (for SHORT straddle) */
  pnlPoints: number;

  /** P&L in INR: pnlPoints * quantity */
  pnlAmount: number;

  /** Why the trade exited. See "Exit Reason Codes" section. */
  exitReason: string;

  /** true if this trade was opened via auto-restart (not the initial trade) */
  wasRestarted: boolean;
}

// ============================================================
// StrategyInfo — from GET /strategies
// ============================================================
interface StrategyInfo {
  name: string;
  description: string;
  backtestSupported: boolean;
}
```

---

## 4. Request/Response Examples

### 4.1 Minimal Request (Points Mode)

```json
POST /api/backtest/run
Content-Type: application/json
X-User-Id: user-123

{
  "backtestDate": "2025-06-12",
  "strategyType": "SELL_ATM_STRADDLE",
  "instrumentType": "NIFTY",
  "expiryDate": "2025-06-12",
  "stopLossPoints": 2.0,
  "targetPoints": 2.0
}
```

All optional fields use defaults: `lots=1`, `slTargetMode="points"`, `startTime="09:20"`, `endTime="15:30"`, `autoSquareOffTime="15:10"`, `candleInterval="minute"`, `autoRestartEnabled=true`, `maxAutoRestarts=0` (unlimited).

### 4.2 Full Request (Premium Mode with Trailing Stop)

```json
POST /api/backtest/run
Content-Type: application/json
X-User-Id: user-123

{
  "backtestDate": "2025-06-12",
  "strategyType": "SELL_ATM_STRADDLE",
  "instrumentType": "BANKNIFTY",
  "expiryDate": "2025-06-12",
  "lots": 2,
  "slTargetMode": "premium",
  "targetDecayPct": 3.5,
  "stopLossExpansionPct": 7.0,
  "startTime": "09:20",
  "endTime": "15:30",
  "autoSquareOffTime": "15:10",
  "candleInterval": "minute",
  "autoRestartEnabled": true,
  "maxAutoRestarts": 3,
  "trailingStopEnabled": true,
  "trailingActivationPoints": 2.5,
  "trailingDistancePoints": 1.5
}
```

### 4.3 Successful Response

```json
{
  "success": true,
  "message": "Backtest completed successfully",
  "data": {
    "backtestId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "backtestDate": "2025-06-12",
    "strategyType": "SELL_ATM_STRADDLE",
    "instrumentType": "NIFTY",
    "status": "COMPLETED",
    "errorMessage": null,
    "spotPriceAtEntry": 24532.15,
    "atmStrike": 24550.0,
    "trades": [
      {
        "tradeNumber": 1,
        "ceSymbol": "NIFTY2561224550CE",
        "peSymbol": "NIFTY2561224550PE",
        "strikePrice": 24550.0,
        "entryTime": "2025-06-12T09:20:00",
        "ceEntryPrice": 125.50,
        "peEntryPrice": 110.25,
        "combinedEntryPremium": 235.75,
        "exitTime": "2025-06-12T10:45:00",
        "ceExitPrice": 130.00,
        "peExitPrice": 103.50,
        "combinedExitPremium": 233.50,
        "quantity": 75,
        "pnlPoints": 2.25,
        "pnlAmount": 168.75,
        "exitReason": "CUMULATIVE_TARGET_HIT (Signal: 2.25 points)",
        "wasRestarted": false
      },
      {
        "tradeNumber": 2,
        "ceSymbol": "NIFTY2561224550CE",
        "peSymbol": "NIFTY2561224550PE",
        "strikePrice": 24550.0,
        "entryTime": "2025-06-12T10:50:00",
        "ceEntryPrice": 128.00,
        "peEntryPrice": 105.00,
        "combinedEntryPremium": 233.00,
        "exitTime": "2025-06-12T15:10:00",
        "ceExitPrice": 115.00,
        "peExitPrice": 95.50,
        "combinedExitPremium": 210.50,
        "quantity": 75,
        "pnlPoints": 22.50,
        "pnlAmount": 1687.50,
        "exitReason": "TIME_BASED_FORCED_EXIT @ 15:10",
        "wasRestarted": true
      }
    ],
    "totalPnLPoints": 24.75,
    "totalPnLAmount": 1856.25,
    "totalTrades": 2,
    "winningTrades": 2,
    "losingTrades": 0,
    "winRate": 100.0,
    "maxDrawdownPct": 0.0,
    "maxProfitPct": 10.49,
    "avgWinAmount": 928.13,
    "avgLossAmount": 0.0,
    "profitFactor": 999.99,
    "restartCount": 1,
    "executionDurationMs": 2847
  }
}
```

### 4.4 Failed Response (Validation Error)

```json
HTTP 400
{
  "success": false,
  "message": "Backtest date is a weekend: 2025-06-14 (SATURDAY)",
  "data": null
}
```

### 4.5 Failed Response (Data Fetch Error — returned as 200 with FAILED status)

```json
{
  "success": true,
  "message": "Backtest failed: Could not find ATM CE/PE instruments for NIFTY strike=24550 expiry=2025-06-12. CE found: false, PE found: false. This may happen if the expiry has passed and instruments are no longer in the current dump.",
  "data": {
    "backtestId": "...",
    "backtestDate": "2025-06-12",
    "strategyType": "SELL_ATM_STRADDLE",
    "instrumentType": "NIFTY",
    "status": "FAILED",
    "errorMessage": "Could not find ATM CE/PE instruments for NIFTY ...",
    "trades": [],
    "totalPnLPoints": 0,
    "totalPnLAmount": 0,
    "totalTrades": 0,
    "executionDurationMs": 1203
  }
}
```

**Important:** A FAILED backtest returns HTTP 200 with `status: "FAILED"` in the data. Only validation-level errors (weekend date, missing fields) return HTTP 400.

### 4.6 Batch Response

```json
POST /api/backtest/batch?fromDate=2025-06-09&toDate=2025-06-13
{
  "success": true,
  "message": "Batch backtest completed: 4/5 days processed",
  "data": [
    { "backtestId": "...", "backtestDate": "2025-06-09", "status": "COMPLETED", ... },
    { "backtestId": "...", "backtestDate": "2025-06-10", "status": "COMPLETED", ... },
    { "backtestId": "...", "backtestDate": "2025-06-11", "status": "FAILED", "errorMessage": "No candle data...", ... },
    { "backtestId": "...", "backtestDate": "2025-06-12", "status": "COMPLETED", ... },
    { "backtestId": "...", "backtestDate": "2025-06-13", "status": "COMPLETED", ... }
  ]
}
```

Note: Weekends (June 14–15) are automatically skipped, not returned in results.

### 4.7 Async Response + Poll

```json
// Step 1: Start
POST /api/backtest/run-async
→ { "success": true, "message": "Backtest started. Poll /api/backtest/result/abc-123...", "data": "abc-123-def-456" }

// Step 2: Poll (still running)
GET /api/backtest/result/abc-123-def-456
→ { "success": true, "message": "Success", "data": { "status": "RUNNING", "trades": [], ... } }

// Step 3: Poll (completed)
GET /api/backtest/result/abc-123-def-456
→ { "success": true, "message": "Success", "data": { "status": "COMPLETED", "trades": [...], ... } }
```

### 4.8 Strategies Response

```json
GET /api/backtest/strategies
{
  "success": true,
  "message": "Success",
  "data": [
    {
      "name": "SELL_ATM_STRADDLE",
      "description": "Sell ATM Call + Put (short straddle) - profits from low volatility and time decay",
      "backtestSupported": true
    },
    {
      "name": "ATM_STRADDLE",
      "description": "Buy ATM Call + Put (long straddle) - profits from high volatility",
      "backtestSupported": false
    }
  ]
}
```

---

## 5. Exit Reason Codes

These are the possible values for `BacktestTrade.exitReason`. Use these for display labels and color coding.

### Points-Based Mode (`slTargetMode = "points"`)

| Exit Reason Pattern | Meaning | UI Label | Color |
|---|---|---|---|
| `CUMULATIVE_TARGET_HIT (Signal: X.XX points)` | Combined P&L reached target | Target Hit | Green |
| `CUMULATIVE_STOPLOSS_HIT (Signal: -X.XX points)` | Combined P&L hit stop loss | Stop Loss Hit | Red |

### Premium-Based Mode (`slTargetMode = "premium"`)

| Exit Reason Pattern | Meaning | UI Label | Color |
|---|---|---|---|
| `PREMIUM_DECAY_TARGET_HIT (Combined LTP: X.XX, Entry: X.XX, TargetLevel: X.XX)` | Combined premium decayed to target level (profit for SHORT) | Premium Target | Green |
| `PREMIUM_EXPANSION_SL_HIT (Combined LTP: X.XX, Entry: X.XX, SL Level: X.XX)` | Combined premium expanded to SL level (loss for SHORT) | Premium SL | Red |

### Trailing Stop

| Exit Reason Pattern | Meaning | UI Label | Color |
|---|---|---|---|
| `TRAILING_STOPLOSS_HIT (Signal: X.XX points, Trail: X.XX)` | Trailing stop triggered after profit retracement | Trailing SL | Amber |

### Time & Data

| Exit Reason | Meaning | UI Label | Color |
|---|---|---|---|
| `TIME_BASED_FORCED_EXIT @ HH:mm` | Auto square-off at configured time | Auto Square Off | Blue |
| `END_OF_DATA` | Reached end of available candle data | End of Data | Gray |

### Parsing Tip

To extract a clean label from the exit reason string:

```typescript
function getExitLabel(exitReason: string): { label: string; color: string } {
  if (exitReason.includes("TARGET_HIT"))
    return { label: "Target Hit", color: "green" };
  if (exitReason.includes("STOPLOSS_HIT"))
    return { label: "Stop Loss", color: "red" };
  if (exitReason.includes("PREMIUM_DECAY"))
    return { label: "Premium Target", color: "green" };
  if (exitReason.includes("PREMIUM_EXPANSION"))
    return { label: "Premium SL", color: "red" };
  if (exitReason.includes("TRAILING"))
    return { label: "Trailing SL", color: "amber" };
  if (exitReason.includes("TIME_BASED_FORCED_EXIT"))
    return { label: "Auto Square Off", color: "blue" };
  if (exitReason.includes("END_OF_DATA"))
    return { label: "End of Data", color: "gray" };
  return { label: exitReason, color: "gray" };
}
```

---

## 6. Validation Rules & Error Handling

### Request Validation (HTTP 400)

| Field | Rule | Error Message |
|---|---|---|
| `backtestDate` | Required, not null | `"Backtest date is required"` |
| `backtestDate` | Must be today or earlier | `"Backtest date must not be in the future"` |
| `backtestDate` | Must not be Saturday/Sunday | `"Backtest date is a weekend: 2025-06-14 (SATURDAY)"` |
| `strategyType` | Required, must be valid enum | `"Strategy type is required"` |
| `instrumentType` | Required | `"Instrument type is required"` |
| `expiryDate` | Required | `"Expiry date is required"` |

### Business Errors (HTTP 200, status = FAILED)

| Error Code | When | Example Message |
|---|---|---|
| `INSTRUMENT_NOT_FOUND` | Expiry has passed, wrong expiry date, or instrument not in NFO dump | `"Could not find ATM CE/PE instruments for NIFTY strike=24550 expiry=..."` |
| `DATA_FETCH_FAILED` | Kite API error, non-trading day (holiday), or no candle data | `"No candle data returned for token 12345 on 2025-06-12"` |
| `BACKTEST_DISABLED` | `backtest.enabled=false` in config | `"Backtest module is disabled in configuration"` |

### Frontend Error Handling Strategy

```typescript
async function runBacktest(request: BacktestRequest) {
  const response = await fetch("/api/backtest/run", {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-User-Id": userId },
    body: JSON.stringify(request),
  });

  if (response.status === 404) {
    // Result not found (only for GET /result/{id})
    return null;
  }

  const body: ApiResponse<BacktestResult> = await response.json();

  if (response.status === 400) {
    // Validation error
    showErrorToast(body.message);
    return null;
  }

  if (!body.success || !body.data) {
    showErrorToast(body.message);
    return null;
  }

  if (body.data.status === "FAILED") {
    // Business logic error — backtest attempted but failed
    showWarningToast(`Backtest failed: ${body.data.errorMessage}`);
    return body.data; // Still return — UI can show the failed result
  }

  return body.data; // COMPLETED
}
```

---

## 7. Async Polling Pattern

Use this when running long backtests or when you want a responsive UI.

```typescript
async function runBacktestAsync(request: BacktestRequest): Promise<BacktestResult> {
  // Step 1: Start the backtest
  const startRes = await fetch("/api/backtest/run-async", {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-User-Id": userId },
    body: JSON.stringify(request),
  });
  const startBody: ApiResponse<string> = await startRes.json();
  const backtestId = startBody.data!;

  // Step 2: Poll until complete
  while (true) {
    await new Promise((resolve) => setTimeout(resolve, 2000)); // 2s interval

    const pollRes = await fetch(`/api/backtest/result/${backtestId}`, {
      headers: { "X-User-Id": userId },
    });

    if (pollRes.status === 404) continue; // Not ready yet

    const pollBody: ApiResponse<BacktestResult> = await pollRes.json();
    const result = pollBody.data!;

    if (result.status === "COMPLETED" || result.status === "FAILED") {
      return result;
    }

    // status === "RUNNING" → continue polling
  }
}
```

---

## 8. Recommended UI Components

### 8.1 Backtest Configuration Form

| Field | Input Type | Notes |
|---|---|---|
| `backtestDate` | Date picker | Disable future dates and weekends |
| `strategyType` | Dropdown | Fetch from `GET /strategies`, only show `backtestSupported: true` |
| `instrumentType` | Dropdown / Radio | NIFTY, BANKNIFTY |
| `expiryDate` | Date picker | Weekly/monthly expiry. Must be >= backtestDate |
| `lots` | Number input | Min: 1, Default: 1 |
| `slTargetMode` | Toggle / Radio | "Points" vs "Premium" |
| Points fields | Number inputs | Show when mode = "points": `stopLossPoints`, `targetPoints` |
| Premium fields | Number inputs | Show when mode = "premium": `targetDecayPct` (%), `stopLossExpansionPct` (%) |
| Advanced (collapsible) | Section | `startTime`, `endTime`, `autoSquareOffTime`, `candleInterval`, `autoRestartEnabled`, `maxAutoRestarts`, trailing stop fields |

### 8.2 Results Summary Cards

Display these after a successful backtest:

| Card | Value | Format | Color Logic |
|---|---|---|---|
| **Total P&L** | `totalPnLAmount` | `₹ X,XXX.XX` | Green if > 0, Red if < 0 |
| **P&L Points** | `totalPnLPoints` | `X.XX pts` | Green if > 0, Red if < 0 |
| **Win Rate** | `winRate` | `XX.XX%` | Green if >= 50, Red if < 50 |
| **Profit Factor** | `profitFactor` | `X.XX` | Green if > 1, Red if < 1 |
| **Max Drawdown** | `maxDrawdownPct` | `XX.XX%` | Always Red/Amber |
| **Total Trades** | `totalTrades` | `N` | Neutral |
| **Wins / Losses** | `winningTrades / losingTrades` | `W / L` | Green / Red |
| **Restarts** | `restartCount` | `N` | Neutral |
| **Spot Price** | `spotPriceAtEntry` | `₹ XX,XXX.XX` | Neutral |
| **ATM Strike** | `atmStrike` | `XX,XXX` | Neutral |
| **Execution Time** | `executionDurationMs` | `X.Xs` | Neutral |

### 8.3 Trade History Table

| Column | Source Field | Format |
|---|---|---|
| # | `tradeNumber` | Integer |
| CE Symbol | `ceSymbol` | String |
| PE Symbol | `peSymbol` | String |
| Strike | `strikePrice` | Number |
| Entry Time | `entryTime` | `HH:mm:ss` (extract time part) |
| CE Entry | `ceEntryPrice` | `₹ XXX.XX` |
| PE Entry | `peEntryPrice` | `₹ XXX.XX` |
| Combined Entry | `combinedEntryPremium` | `₹ XXX.XX` |
| Exit Time | `exitTime` | `HH:mm:ss` |
| CE Exit | `ceExitPrice` | `₹ XXX.XX` |
| PE Exit | `peExitPrice` | `₹ XXX.XX` |
| Combined Exit | `combinedExitPremium` | `₹ XXX.XX` |
| Qty | `quantity` | Integer |
| P&L (pts) | `pnlPoints` | `+X.XX` / `-X.XX` |
| P&L (₹) | `pnlAmount` | `₹ +X,XXX.XX` / `₹ -X,XXX.XX` |
| Exit Reason | `exitReason` | Parsed label (see section 5) |
| Restarted | `wasRestarted` | Badge: "Restart" or empty |

**Row color:** Green background for profit rows, Red for loss rows.

### 8.4 Charts

**Equity Curve (Line Chart):**
- X-axis: Trade number (1, 2, 3...)
- Y-axis: Cumulative P&L (₹)
- Compute: `cumulativePnL[i] = cumulativePnL[i-1] + trades[i].pnlAmount`

**P&L Per Trade (Bar Chart):**
- X-axis: Trade number
- Y-axis: `pnlAmount` per trade
- Green bars for profit, Red for loss

**Win/Loss Distribution (Pie/Donut Chart):**
- Segments: `winningTrades` (green), `losingTrades` (red)

### 8.5 Batch Results Table

| Column | Source | Notes |
|---|---|---|
| Date | `backtestDate` | Sortable |
| Status | `status` | Badge: green/red/blue |
| Spot | `spotPriceAtEntry` | |
| ATM Strike | `atmStrike` | |
| Trades | `totalTrades` | |
| P&L (₹) | `totalPnLAmount` | Color coded |
| P&L (pts) | `totalPnLPoints` | |
| Win Rate | `winRate` | |
| Profit Factor | `profitFactor` | |
| Max DD | `maxDrawdownPct` | |
| Restarts | `restartCount` | |

**Click row** → expand to show that day's trades table.

**Batch summary row** at bottom: sum of P&L, average win rate, total trades across all days.

---

## 9. API Client Reference Implementation

A ready-to-use API client using `fetch`:

```typescript
const BASE_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";
const USER_ID = "default-user"; // or from auth context

const headers = (json = false) => ({
  ...(json ? { "Content-Type": "application/json" } : {}),
  "X-User-Id": USER_ID,
});

export const backtestApi = {
  /** Run single-day backtest (synchronous) */
  async run(request: BacktestRequest): Promise<ApiResponse<BacktestResult>> {
    const res = await fetch(`${BASE_URL}/api/backtest/run`, {
      method: "POST",
      headers: headers(true),
      body: JSON.stringify(request),
    });
    return res.json();
  },

  /** Run batch backtest over date range */
  async batch(
    fromDate: string,
    toDate: string,
    request: BacktestRequest
  ): Promise<ApiResponse<BacktestResult[]>> {
    const res = await fetch(
      `${BASE_URL}/api/backtest/batch?fromDate=${fromDate}&toDate=${toDate}`,
      {
        method: "POST",
        headers: headers(true),
        body: JSON.stringify(request),
      }
    );
    return res.json();
  },

  /** Start async backtest, returns backtestId */
  async runAsync(request: BacktestRequest): Promise<ApiResponse<string>> {
    const res = await fetch(`${BASE_URL}/api/backtest/run-async`, {
      method: "POST",
      headers: headers(true),
      body: JSON.stringify(request),
    });
    return res.json();
  },

  /** Get result by ID (for polling) */
  async getResult(
    backtestId: string
  ): Promise<ApiResponse<BacktestResult> | null> {
    const res = await fetch(
      `${BASE_URL}/api/backtest/result/${backtestId}`,
      { headers: headers() }
    );
    if (res.status === 404) return null;
    return res.json();
  },

  /** Get all cached results */
  async getAllResults(): Promise<ApiResponse<BacktestResult[]>> {
    const res = await fetch(`${BASE_URL}/api/backtest/results`, {
      headers: headers(),
    });
    return res.json();
  },

  /** Get supported strategies */
  async getStrategies(): Promise<ApiResponse<StrategyInfo[]>> {
    const res = await fetch(`${BASE_URL}/api/backtest/strategies`, {
      headers: headers(),
    });
    return res.json();
  },

  /** Clear result cache */
  async clearCache(): Promise<ApiResponse<null>> {
    const res = await fetch(`${BASE_URL}/api/backtest/cache`, {
      method: "DELETE",
      headers: headers(),
    });
    return res.json();
  },
};
```

---

## 10. Important Business Logic Notes

### P&L Calculation

This is a **SHORT straddle** (SELL_ATM_STRADDLE):
- **P&L (points)** = `combinedEntryPremium - combinedExitPremium`
- **P&L (INR)** = `pnlPoints × quantity`
- Positive P&L = premium decayed (profit for seller)
- Negative P&L = premium expanded (loss for seller)

### Lot Sizes

| Instrument | Lot Size | Strike Interval |
|---|---|---|
| NIFTY | 75 (may change per SEBI) | 50 |
| BANKNIFTY | 30 (may change per SEBI) | 100 |

Lot size is resolved at runtime from Kite's instrument dump. If not found, defaults above are used.

### Auto-Restart Behavior

When `autoRestartEnabled: true`:
1. After SL or target hit, simulation fast-forwards to the **next 5-minute candle boundary**
2. A new entry is made at that candle's close price
3. `wasRestarted: true` is set on the new trade
4. This continues until `maxAutoRestarts` is reached, auto square-off time, or end of data

### Expiry Date Constraint

The `expiryDate` must correspond to an **active expiry** in Kite's current instrument dump. If you're backtesting a date from 3 months ago but that expiry has already passed, the instruments may not be available. For best results, backtest dates **within the current expiry series**.

### Time Boundaries

| Time | Purpose |
|---|---|
| `startTime` (default 09:20) | First possible entry. The 09:15 candle is used for spot price, first entry at 09:20. |
| `autoSquareOffTime` (default 15:10) | No new entries at or after this time. Any open position is force-closed. |
| `endTime` (default 15:30) | Absolute simulation boundary. No tick processing after this. |

### Data Resolution

The smallest candle interval available from Kite API is **1 minute** (`"minute"`). There is no second-level data available from the historical API. Each minute candle's **close price** is used as the simulated LTP.

### Cache Behavior

- Results are stored in an **in-memory** `ConcurrentHashMap` (max 100 entries by default)
- Results are **lost on server restart** (Cloud Run cold starts)
- Oldest entry is evicted when the cache is full
- Use `DELETE /cache` to manually clear

### Rate Limiting

The backend enforces a **350ms delay** between Kite API calls to respect Kite's 3 req/sec limit. A single-day backtest makes 3 API calls (1 spot + 1 CE + 1 PE), so minimum latency is ~1 second just for data fetching.

---

## Color Coding Reference

| Context | Color | Hex |
|---|---|---|
| Profit / Win | Green | `#22c55e` |
| Loss / SL | Red | `#ef4444` |
| Trailing SL | Amber | `#f59e0b` |
| Time Exit / Info | Blue | `#3b82f6` |
| Neutral / Unknown | Gray | `#6b7280` |
| Status: COMPLETED | Green | `#22c55e` |
| Status: FAILED | Red | `#ef4444` |
| Status: RUNNING | Blue | `#3b82f6` (with pulse animation) |

