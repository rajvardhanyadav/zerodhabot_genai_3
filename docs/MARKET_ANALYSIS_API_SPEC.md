# Market Analysis API — Neutral Market Detection Logs

> **Base URL:** `/api/market-analysis`  
> **Backend:** Spring Boot 3.x · Java 17 · PostgreSQL (Cloud SQL)  
> **Auth:** `X-User-Id` header required on all requests  
> **Date:** 2026-03-29

---

## Overview

The V3 Neutral Market Detection Engine evaluates market conditions every **30 seconds** during market hours (09:15–15:10 IST) and persists a complete log of each evaluation. These APIs let you query and analyse those logs by date.

Each evaluation runs a **3-layer detection model**:

| Layer | Signals | Score Range | Purpose |
|---|---|---|---|
| **Regime** (R1–R5) | VWAP Proximity, Range Compression, Oscillation, ADX Trend, Gamma Pin | 0–9 | Macro neutrality classification |
| **Microstructure** (M1–M3) | VWAP Pullback, HF Oscillation, Micro Range Stability | 0–5 | Immediate entry timing |
| **Veto Gates** | Breakout Risk, Excessive Range | pass/fail | Safety gates that block entry |

**Final decision:** `tradable = (no veto fired) AND (regimeScore >= threshold)`

**Volume:** ~750 rows/day (1 eval per 30s × 6.25 market hours).

---

## Standard Response Wrapper

Every response is wrapped in:

```json
{
  "success": true,
  "message": "Human-readable message",
  "data": { ... }
}
```

On error:

```json
{
  "success": false,
  "message": "Error description",
  "data": null
}
```

**TypeScript type:**

```typescript
interface ApiResponse<T> {
  success: boolean;
  message: string;
  data: T | null;
}
```

---

## Endpoints

### 1. `GET /api/market-analysis/neutral-market-logs`

Retrieve individual evaluation logs. Each log is one 30-second evaluation snapshot.

#### Query Parameters

| Param | Type | Required | Default | Description |
|---|---|---|---|---|
| `date` | `string` (yyyy-MM-dd) | No | today | Single date to query |
| `from` | `string` (yyyy-MM-dd) | No | — | Start date for range query (inclusive) |
| `to` | `string` (yyyy-MM-dd) | No | — | End date for range query (inclusive) |
| `instrument` | `string` | No | — | Filter by instrument (e.g., `NIFTY`) |

**Priority:** If `date` is provided, it takes precedence. If `date` is absent and both `from`+`to` are provided, a range query runs. If nothing is provided, defaults to today.

#### Response: `ApiResponse<NeutralMarketLog[]>`

```json
{
  "success": true,
  "message": "Retrieved 742 neutral market evaluation logs",
  "data": [
    {
      "id": 15234,
      "instrument": "NIFTY",
      "evaluatedAt": "2026-03-29T14:30:15",
      "tradingDate": "2026-03-29",
      "spotPrice": 24150.35,
      "vwapValue": 24142.80,

      "tradable": true,
      "regime": "STRONG_NEUTRAL",
      "breakoutRisk": "LOW",
      "vetoReason": null,

      "regimeScore": 7,
      "microScore": 3,
      "finalScore": 10,
      "confidence": 0.67,
      "timeAdjustment": 0,
      "microTradable": true,

      "vwapProximityPassed": true,
      "vwapDeviation": 0.00031,
      "rangeCompressionPassed": true,
      "rangeFraction": 0.0042,
      "oscillationPassed": true,
      "oscillationReversals": 5,
      "adxPassed": true,
      "adxValue": 18.45,
      "gammaPinPassed": false,
      "expiryDay": false,

      "microVwapPullbackPassed": true,
      "microHfOscillationPassed": false,
      "microRangeStabilityPassed": true,

      "breakoutRiskLow": true,
      "excessiveRangeSafe": true,

      "summary": "R[V✓ RC✓ O✓ A✓]=7 M[VP✓ HF✗ RS✓]=3 BR=LOW → TRADE",
      "evaluationDurationMs": 45,
      "createdAt": "2026-03-29T14:30:15"
    }
  ]
}
```

#### Error Response (400)

```json
{
  "success": false,
  "message": "'from' date must be before or equal to 'to' date",
  "data": null
}
```

---

### 2. `GET /api/market-analysis/neutral-market-summary`

Aggregated statistics for a single trading day. Use this for dashboard summary cards and charts.

#### Query Parameters

| Param | Type | Required | Default | Description |
|---|---|---|---|---|
| `date` | `string` (yyyy-MM-dd) | No | today | Date to summarize |

#### Response: `ApiResponse<NeutralMarketSummary>`

```json
{
  "success": true,
  "message": "Neutral market summary for 2026-03-29",
  "data": {
    "date": "2026-03-29",
    "totalEvaluations": 742,
    "tradableCount": 485,
    "skippedCount": 257,
    "tradablePercentage": 65.36,

    "avgRegimeScore": 5.42,
    "avgMicroScore": 2.18,
    "avgConfidence": 0.505,
    "avgEvaluationDurationMs": 38.5,

    "regimeDistribution": {
      "STRONG_NEUTRAL": 310,
      "WEAK_NEUTRAL": 175,
      "TRENDING": 257
    },

    "vetoReasonDistribution": {
      "BREAKOUT_HIGH": 42,
      "EXCESSIVE_RANGE": 15
    },

    "signalPassRates": {
      "VWAP_PROXIMITY": "580/742 (78.17%)",
      "RANGE_COMPRESSION": "495/742 (66.71%)",
      "OSCILLATION": "410/742 (55.26%)",
      "ADX_TREND": "620/742 (83.56%)",
      "GAMMA_PIN": "0/742 (0.0%)",
      "MICRO_VWAP_PULLBACK": "320/742 (43.13%)",
      "MICRO_HF_OSCILLATION": "280/742 (37.74%)",
      "MICRO_RANGE_STABILITY": "450/742 (60.65%)"
    }
  }
}
```

---

## Data Model Reference

### `NeutralMarketLog` — Full Field Listing

#### Evaluation Context

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | `number` | No | Auto-generated primary key |
| `instrument` | `string` | No | Instrument evaluated, e.g., `"NIFTY"` |
| `evaluatedAt` | `string` (ISO datetime) | No | When the evaluation ran (IST), e.g., `"2026-03-29T10:15:30"` |
| `tradingDate` | `string` (ISO date) | No | Trading day, e.g., `"2026-03-29"` |
| `spotPrice` | `number` | No | NIFTY spot price at evaluation time |
| `vwapValue` | `number` | Yes | Computed VWAP; `null` or `0` if unavailable |

#### Final Decision

| Field | Type | Nullable | Description |
|---|---|---|---|
| `tradable` | `boolean` | No | `true` = market is tradable, `false` = skip |
| `regime` | `string` | No | One of: `"STRONG_NEUTRAL"`, `"WEAK_NEUTRAL"`, `"TRENDING"` |
| `breakoutRisk` | `string` | No | One of: `"LOW"`, `"MEDIUM"`, `"HIGH"` |
| `vetoReason` | `string` | Yes | `null` if tradable; otherwise `"BREAKOUT_HIGH"` or `"EXCESSIVE_RANGE"` |

#### Scores

| Field | Type | Nullable | Range | Description |
|---|---|---|---|---|
| `regimeScore` | `integer` | No | 0–9 | Macro neutrality score (VWAP + Range + Oscillation + ADX + Gamma) |
| `microScore` | `integer` | No | 0–5 | Microstructure score (Pullback + HF Oscillation + Range Stability) |
| `finalScore` | `integer` | No | 0–15 | `regimeScore + microScore + timeAdjustment`, clamped 0–15 |
| `confidence` | `number` | No | 0.0–1.0 | `finalScore / 15.0` |
| `timeAdjustment` | `integer` | Yes | −1 to +1 | Time-based bonus/penalty: −1 (opening session 09:15–09:40), +1 (closing session 13:30–15:00) |
| `microTradable` | `boolean` | No | — | Whether micro layer independently says tradable (`microScore ≥ 2`) |

#### Regime Layer Signals (R1–R5)

| Field | Type | Nullable | Description |
|---|---|---|---|
| `vwapProximityPassed` | `boolean` | No | R1: `\|price − VWAP\| / VWAP < 0.4%` |
| `vwapDeviation` | `number` | Yes | R1 raw value: the actual deviation fraction (e.g., `0.00031`) |
| `rangeCompressionPassed` | `boolean` | No | R2: `(highest − lowest) / price < 0.6%` over 10 candles |
| `rangeFraction` | `number` | Yes | R2 raw value: the actual range fraction (e.g., `0.0042`) |
| `oscillationPassed` | `boolean` | No | R3: direction reversals ≥ 3 in last 10 candles |
| `oscillationReversals` | `integer` | Yes | R3 raw value: actual reversal count (e.g., `5`) |
| `adxPassed` | `boolean` | No | R4: ADX(7) < 25.0 (ranging market) |
| `adxValue` | `number` | Yes | R4 raw value: latest ADX reading (e.g., `18.45`) |
| `gammaPinPassed` | `boolean` | No | R5: spot within 0.2% of max-OI strike (expiry day only; always `false` on non-expiry days) |
| `expiryDay` | `boolean` | No | Whether this evaluation was on an options expiry day |

#### Microstructure Layer Signals (M1–M3)

| Field | Type | Nullable | Description |
|---|---|---|---|
| `microVwapPullbackPassed` | `boolean` | No | M1: price deviated from VWAP then reverting back toward it |
| `microHfOscillationPassed` | `boolean` | No | M2: high flip count + small amplitude in short window |
| `microRangeStabilityPassed` | `boolean` | No | M3: last 5 candles in tight range (<0.3%) |

#### Veto Gate Signals

| Field | Type | Nullable | Description |
|---|---|---|---|
| `breakoutRiskLow` | `boolean` | No | `true` = breakout risk is LOW (safe); `false` = MEDIUM or HIGH |
| `excessiveRangeSafe` | `boolean` | No | `true` = range is normal; `false` = excessive range veto fired |

#### Summary & Performance

| Field | Type | Nullable | Description |
|---|---|---|---|
| `summary` | `string` | Yes | Compact signal summary, e.g., `"R[V✓ RC✓ O✗ A✓]=5 M[VP✓ HF✗ RS✓]=3 BR=LOW → TRADE"` |
| `evaluationDurationMs` | `number` | Yes | How long the evaluation took in milliseconds |
| `createdAt` | `string` (ISO datetime) | No | When the record was persisted |

---

### `NeutralMarketSummary` — Aggregate Fields

| Field | Type | Description |
|---|---|---|
| `date` | `string` | The summarized date |
| `totalEvaluations` | `number` | Total evaluation count for the day (~750) |
| `tradableCount` | `number` | Number of evaluations where `tradable = true` |
| `skippedCount` | `number` | Number of evaluations where `tradable = false` |
| `tradablePercentage` | `number` | `tradableCount / totalEvaluations * 100`, rounded to 2 decimals |
| `avgRegimeScore` | `number \| null` | Average regime score (0–9), rounded to 2 decimals |
| `avgMicroScore` | `number \| null` | Average micro score (0–5), rounded to 2 decimals |
| `avgConfidence` | `number \| null` | Average confidence (0.0–1.0), rounded to 3 decimals |
| `avgEvaluationDurationMs` | `number \| null` | Average eval duration in ms, rounded to 1 decimal |
| `regimeDistribution` | `Record<string, number>` | Count per regime: `{ "STRONG_NEUTRAL": 310, "WEAK_NEUTRAL": 175, "TRENDING": 257 }` |
| `vetoReasonDistribution` | `Record<string, number>` | Count per veto reason: `{ "BREAKOUT_HIGH": 42, "EXCESSIVE_RANGE": 15 }`. Empty `{}` if no vetoes. |
| `signalPassRates` | `Record<string, string>` | Per-signal pass rate: `{ "VWAP_PROXIMITY": "580/742 (78.17%)", ... }` |

---

## Enum / Constant Reference

### `regime`
| Value | Score Range | Meaning |
|---|---|---|
| `STRONG_NEUTRAL` | regimeScore ≥ 6 | High confidence — full position allowed |
| `WEAK_NEUTRAL` | regimeScore ≥ 3 | Moderate confidence — reduced position recommended |
| `TRENDING` | regimeScore < 3 | Market is trending — straddle entry should be skipped |

### `breakoutRisk`
| Value | Meaning |
|---|---|
| `LOW` | 0–1 breakout signals present — safe to enter |
| `MEDIUM` | 2 breakout signals — caution / reduced size |
| `HIGH` | All 3 breakout signals — **do NOT enter** (hard veto) |

### `vetoReason`
| Value | Meaning |
|---|---|
| `null` | No veto — trade is allowed |
| `BREAKOUT_HIGH` | Breakout risk layer assessed HIGH — entry blocked |
| `EXCESSIVE_RANGE` | Recent candle range exceeds 0.8% — entry blocked |

### Signal Weight Reference
| Signal | Weight | Max Contribution |
|---|---|---|
| VWAP Proximity (R1) | 3 | 3 |
| Range Compression (R2) | 2 | 2 |
| Oscillation (R3) | 2 | 2 |
| ADX Trend (R4) | 1 | 1 |
| Gamma Pin (R5, expiry only) | 1 | 1 |
| Micro VWAP Pullback (M1) | 2 | 2 |
| Micro HF Oscillation (M2) | 2 | 2 |
| Micro Range Stability (M3) | 1 | 1 |

---

## TypeScript Interfaces

```typescript
// === Standard API wrapper ===
interface ApiResponse<T> {
  success: boolean;
  message: string;
  data: T | null;
}

// === Individual evaluation log ===
interface NeutralMarketLog {
  id: number;
  instrument: string;
  evaluatedAt: string;          // ISO datetime "2026-03-29T14:30:15"
  tradingDate: string;          // ISO date "2026-03-29"
  spotPrice: number;
  vwapValue: number | null;

  // Final decision
  tradable: boolean;
  regime: 'STRONG_NEUTRAL' | 'WEAK_NEUTRAL' | 'TRENDING';
  breakoutRisk: 'LOW' | 'MEDIUM' | 'HIGH';
  vetoReason: string | null;    // "BREAKOUT_HIGH" | "EXCESSIVE_RANGE" | null

  // Scores
  regimeScore: number;          // 0–9
  microScore: number;           // 0–5
  finalScore: number;           // 0–15
  confidence: number;           // 0.0–1.0
  timeAdjustment: number | null;
  microTradable: boolean;

  // Regime layer signals (R1–R5)
  vwapProximityPassed: boolean;
  vwapDeviation: number | null;
  rangeCompressionPassed: boolean;
  rangeFraction: number | null;
  oscillationPassed: boolean;
  oscillationReversals: number | null;
  adxPassed: boolean;
  adxValue: number | null;
  gammaPinPassed: boolean;
  expiryDay: boolean;

  // Microstructure layer signals (M1–M3)
  microVwapPullbackPassed: boolean;
  microHfOscillationPassed: boolean;
  microRangeStabilityPassed: boolean;

  // Veto gate signals
  breakoutRiskLow: boolean;
  excessiveRangeSafe: boolean;

  // Summary & performance
  summary: string | null;
  evaluationDurationMs: number | null;
  createdAt: string;            // ISO datetime
}

// === Daily summary / analytics ===
interface NeutralMarketSummary {
  date: string;
  totalEvaluations: number;
  tradableCount: number;
  skippedCount: number;
  tradablePercentage: number;

  avgRegimeScore: number | null;
  avgMicroScore: number | null;
  avgConfidence: number | null;
  avgEvaluationDurationMs: number | null;

  regimeDistribution: Record<string, number>;      // e.g. { "STRONG_NEUTRAL": 310 }
  vetoReasonDistribution: Record<string, number>;   // e.g. { "BREAKOUT_HIGH": 42 }
  signalPassRates: Record<string, string>;          // e.g. { "VWAP_PROXIMITY": "580/742 (78.17%)" }
}
```

---

## Usage Examples

### Fetch today's logs
```
GET /api/market-analysis/neutral-market-logs
```

### Fetch logs for a specific date
```
GET /api/market-analysis/neutral-market-logs?date=2026-03-28
```

### Fetch logs for a date range
```
GET /api/market-analysis/neutral-market-logs?from=2026-03-25&to=2026-03-29
```

### Fetch logs for NIFTY on a specific date
```
GET /api/market-analysis/neutral-market-logs?date=2026-03-29&instrument=NIFTY
```

### Fetch daily summary
```
GET /api/market-analysis/neutral-market-summary?date=2026-03-29
```

---

## UI Suggestions

### Dashboard Cards (from summary endpoint)
- **Tradable %** — donut/pie chart: tradable vs skipped
- **Avg Confidence** — gauge widget (0.0–1.0)
- **Regime Distribution** — horizontal bar chart (STRONG/WEAK/TRENDING)
- **Veto Reasons** — small bar chart or badge count

### Signal Pass Rate Table (from summary endpoint)
- 8-row table with signal name, pass count, total, percentage
- Color-code: green ≥70%, yellow 40–70%, red <40%

### Timeline View (from logs endpoint)
- X-axis = `evaluatedAt` (time of day 09:15–15:10), Y-axis = `finalScore` or `regimeScore`
- Color each point by `regime` (green=STRONG_NEUTRAL, yellow=WEAK_NEUTRAL, red=TRENDING)
- Mark vetoed points with ✗ icon
- Tooltip on hover shows full signal breakdown

### Log Detail Table (from logs endpoint)
- Sortable, filterable table with key columns:
  - Time, Spot Price, Regime, Score, Confidence, Tradable, Veto Reason
- Expand row to show all 10 signal pass/fail with raw numeric values
- Filter by: regime, tradable, veto reason, date range

### Date Picker
- Single-date picker for both endpoints
- Range picker for logs endpoint
- Default to today

