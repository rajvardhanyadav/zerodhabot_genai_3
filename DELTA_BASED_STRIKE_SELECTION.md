# Delta-Based Strike Selection Implementation

## Overview
Implemented delta-based ATM strike selection for the ATM Straddle Strategy. Instead of simply selecting the strike closest to the spot price, the system now selects the strike with delta values nearest to ±0.5, which represents true "at-the-money" options in terms of probability.

## What is Delta?
Delta measures the rate of change of an option's price with respect to changes in the underlying asset's price:
- **Call Delta**: Ranges from 0 to 1 (typically 0.5 for ATM)
- **Put Delta**: Ranges from -1 to 0 (typically -0.5 for ATM)
- **ATM Options**: Options with delta around ±0.5 have approximately 50% probability of expiring in-the-money

## Implementation Details

### 1. Black-Scholes Model
The implementation uses the Black-Scholes option pricing model to calculate option Greeks:

**Formula for Call Delta:**
```
Delta = N(d1)
where:
d1 = [ln(S/K) + (r + σ²/2)T] / (σ√T)
```

**Components:**
- `S` = Spot price
- `K` = Strike price
- `r` = Risk-free rate (5% annualized)
- `σ` = Volatility (15% annualized - approximate)
- `T` = Time to expiry (in years)
- `N(d1)` = Cumulative normal distribution

### 2. New Methods in BaseStrategy

#### `getATMStrikeByDelta()`
```java
protected double getATMStrikeByDelta(double spotPrice, String instrumentType, 
                                     List<Instrument> instruments, Date expiry)
```

**How it works:**
1. Calculates approximate ATM using traditional method
2. Gets ±5 strikes around the approximate ATM
3. Calculates call delta for each strike
4. Selects the strike with delta closest to 0.5
5. Falls back to traditional method if time to expiry is zero

**Parameters:**
- `spotPrice`: Current spot price of the underlying
- `instrumentType`: NIFTY, BANKNIFTY, or FINNIFTY
- `instruments`: List of available option instruments
- `expiry`: Expiry date of the options

**Returns:** Strike price with delta nearest to ±0.5

#### `calculateCallDelta()`
Calculates the delta of a call option using Black-Scholes model.

#### `calculatePutDelta()`
Calculates the delta of a put option (Call Delta - 1).

#### `calculateTimeToExpiry()`
Calculates time remaining until expiry in years, considering market close time (3:30 PM IST).

#### `cumulativeNormalDistribution()`
Implements the cumulative normal distribution function using error function approximation.

#### `erf()`
Error function approximation using Abramowitz and Stegun formula.

### 3. Updated ATMStraddleStrategy

The `execute()` method now:
1. Fetches option instruments first
2. Extracts expiry date from instruments
3. Uses `getATMStrikeByDelta()` if expiry date is available
4. Falls back to traditional `getATMStrike()` if no expiry date found

**Code:**
```java
// Get expiry date from instruments for delta calculation
Date expiryDate = instruments.isEmpty() ? null : instruments.get(0).expiry;

// Calculate ATM strike using delta-based selection (nearest to ±0.5)
double atmStrike = expiryDate != null 
    ? getATMStrikeByDelta(spotPrice, request.getInstrumentType(), instruments, expiryDate)
    : getATMStrike(spotPrice, request.getInstrumentType());

log.info("ATM Strike (Delta-based): {}", atmStrike);
```

## Constants Used

### Risk-Free Rate: 5%
Approximate annual risk-free rate (based on Indian government securities).

### Volatility: 15%
Approximate annualized implied volatility. This is a fixed value for simplicity.

**Note:** In production, you might want to:
- Fetch actual implied volatility from market data
- Use different volatility values for different instruments
- Adjust volatility based on VIX India or similar volatility indices

## Advantages of Delta-Based Selection

### 1. **More Accurate ATM Selection**
- Traditional method: Picks strike closest to spot price mathematically
- Delta method: Picks strike with 50% probability of being ITM (true ATM)

### 2. **Better for Volatile Markets**
- Delta accounts for volatility in strike selection
- More appropriate strike during high/low volatility periods

### 3. **Time Decay Consideration**
- Delta calculation includes time to expiry
- Near expiry, deltas shift faster - selection adapts accordingly

### 4. **Standardized Across Instruments**
- Works consistently for NIFTY, BANKNIFTY, FINNIFTY
- Accounts for different strike intervals automatically

## Example Scenario

**Traditional Method:**
- Spot: 19,555
- Selected Strike: 19,550 (nearest 50-point interval)
- Call Delta: 0.48, Put Delta: -0.52

**Delta-Based Method:**
- Spot: 19,555
- Strike 19,550: Call Delta = 0.48
- Strike 19,600: Call Delta = 0.52
- Selected Strike: 19,550 or 19,600 (whichever is closer to 0.5)

## Logging

The implementation includes detailed logging:
- Debug logs for each strike's delta calculation
- Info log showing selected strike and delta difference from 0.5
- Warning if falling back to traditional method

**Example Log Output:**
```
DEBUG Strike: 19450.0, Call Delta: 0.62, Delta Diff from 0.5: 0.12
DEBUG Strike: 19500.0, Call Delta: 0.58, Delta Diff from 0.5: 0.08
DEBUG Strike: 19550.0, Call Delta: 0.52, Delta Diff from 0.5: 0.02
DEBUG Strike: 19600.0, Call Delta: 0.48, Delta Diff from 0.5: 0.02
DEBUG Strike: 19650.0, Call Delta: 0.42, Delta Diff from 0.5: 0.08
INFO  Selected strike 19550.0 with delta nearest to 0.5 (difference: 0.02)
```

## Future Enhancements

1. **Dynamic Volatility:**
   - Fetch real-time implied volatility from market
   - Use India VIX or instrument-specific volatility

2. **Risk-Free Rate Updates:**
   - Update based on current RBI repo rate
   - Adjust for short-term vs long-term rates

3. **Strike Range Optimization:**
   - Dynamically adjust ±5 strike range based on volatility
   - Wider range during high volatility periods

4. **Put Delta Validation:**
   - Also validate put delta is near -0.5
   - Ensure both legs are truly ATM

5. **Greeks Display:**
   - Return calculated delta in API response
   - Show Greeks to users for transparency

## Testing Recommendations

1. Test with different spot prices (near strikes vs mid-strikes)
2. Test near market close on expiry day
3. Compare traditional vs delta-based selections
4. Monitor actual P&L differences
5. Test with WEEKLY vs MONTHLY expiries

## Backward Compatibility

The system maintains backward compatibility:
- Falls back to traditional method if expiry date unavailable
- Falls back if time to expiry ≤ 0
- No breaking changes to existing API

## Performance Considerations

- Delta calculation adds minimal overhead (< 100ms)
- Calculations done once during strategy execution
- No impact on real-time monitoring performance
- Results cached in strike selection

