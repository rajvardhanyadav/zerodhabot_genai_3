package com.tradingbot.backtest.engine;

import com.tradingbot.service.TradingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Instrument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.text.SimpleDateFormat;

import java.util.List;

/**
 * Resolves ATM CE/PE instruments for a given spot price and expiry.
 * <p>
 * Designed for repeated calls during backtest simulation — the NFO instrument
 * dump is fetched once per backtest and then reused for every ATM lookup
 * (initial entry + each auto-restart).
 * <p>
 * Uses the current Kite instrument dump (tokens are stable within the same expiry series).
 * For very old dates, tokens may not match — this is a documented v1 limitation.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InstrumentResolver {

    private static final String EXCHANGE_NFO = "NFO";

    private final TradingService tradingService;

    /**
     * Resolved instruments and market data for a specific spot price.
     */
    public record ResolvedInstruments(
            Instrument ceInstrument,
            Instrument peInstrument,
            double spotPrice,
            double atmStrike,
            int lotSize
    ) {}

    /**
     * Resolve ATM CE and PE instruments for an explicit spot price.
     * <p>
     * This is the core method used by the backtest engine at each entry point
     * (initial entry + every auto-restart). The caller provides the spot price
     * at the specific entry time — NOT the 9:15 open price.
     *
     * @param instrumentType   NIFTY or BANKNIFTY
     * @param expiryDate       expiry in yyyy-MM-dd format
     * @param spotPrice        underlying spot price at the entry time
     * @param nfoInstruments   pre-fetched NFO instrument list (call {@link #fetchNfoInstruments()} once)
     * @return resolved instruments with spot price and ATM strike
     */
    public ResolvedInstruments resolveForSpotPrice(String instrumentType, String expiryDate,
                                                     double spotPrice, List<Instrument> nfoInstruments) {
        // Calculate ATM strike
        double strikeInterval = getStrikeInterval(instrumentType);
        double atmStrike = Math.round(spotPrice / strikeInterval) * strikeInterval;
        log.info("ATM strike for {}: {} (spot: {})", instrumentType, atmStrike, String.format("%.2f", spotPrice));

        // Find matching CE/PE from the pre-fetched instrument dump
        String instrumentName = getInstrumentName(instrumentType);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        Instrument ceInstrument = null;
        Instrument peInstrument = null;
        int lotSize = 0;

        for (Instrument inst : nfoInstruments) {
            if (inst.name == null || !inst.name.equals(instrumentName)) continue;
            if (inst.expiry == null || !sdf.format(inst.expiry).equals(expiryDate)) continue;

            double instStrike;
            try {
                instStrike = Double.parseDouble(inst.strike);
            } catch (NumberFormatException e) {
                continue;
            }
            if (instStrike != atmStrike) continue;

            if ("CE".equals(inst.instrument_type)) {
                ceInstrument = inst;
                if (inst.lot_size > 0) lotSize = inst.lot_size;
            } else if ("PE".equals(inst.instrument_type)) {
                peInstrument = inst;
                if (lotSize == 0 && inst.lot_size > 0) lotSize = inst.lot_size;
            }

            if (ceInstrument != null && peInstrument != null) break;
        }

        if (ceInstrument == null || peInstrument == null) {
            throw new BacktestException(BacktestException.ErrorCode.INSTRUMENT_NOT_FOUND,
                    String.format("Could not find ATM CE/PE instruments for %s strike=%.0f expiry=%s (spot=%.2f). "
                            + "CE found: %s, PE found: %s. "
                            + "This may happen if the expiry has passed and instruments are no longer in the current dump.",
                            instrumentType, atmStrike, expiryDate, spotPrice,
                            ceInstrument != null, peInstrument != null));
        }

        if (lotSize <= 0) lotSize = getDefaultLotSize(instrumentType);

        log.info("Resolved instruments: CE={} (token={}), PE={} (token={}), lotSize={}, strike={}",
                ceInstrument.tradingsymbol, ceInstrument.instrument_token,
                peInstrument.tradingsymbol, peInstrument.instrument_token, lotSize, atmStrike);

        return new ResolvedInstruments(ceInstrument, peInstrument, spotPrice, atmStrike, lotSize);
    }

    /**
     * Fetch the NFO instrument dump from Kite. Call this ONCE per backtest
     * and pass the result to {@link #resolveForSpotPrice} for each entry point.
     */
    public List<Instrument> fetchNfoInstruments() {
        try {
            return tradingService.getInstruments(EXCHANGE_NFO);
        } catch (KiteException | IOException e) {
            throw new BacktestException(BacktestException.ErrorCode.DATA_FETCH_FAILED,
                    "Failed to fetch NFO instruments: " + e.getMessage(), e);
        }
    }

    /**
     * Get the NSE index instrument token for spot price lookup.
     */
    public String getIndexToken(String instrumentType) {
        return switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> "256265";      // NSE:NIFTY 50
            case "BANKNIFTY" -> "260105";  // NSE:NIFTY BANK
            default -> throw new BacktestException(BacktestException.ErrorCode.INSTRUMENT_NOT_FOUND,
                    "Unsupported instrument type: " + instrumentType);
        };
    }

    private String getInstrumentName(String instrumentType) {
        return switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> "NIFTY";
            case "BANKNIFTY" -> "BANKNIFTY";
            default -> instrumentType.toUpperCase();
        };
    }

    public double getStrikeInterval(String instrumentType) {
        return switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> 50.0;
            case "BANKNIFTY" -> 100.0;
            default -> 50.0;
        };
    }

    public int getDefaultLotSize(String instrumentType) {
        return switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> 75;
            case "BANKNIFTY" -> 30;
            default -> 50;
        };
    }
}


