package com.tradingbot.util;

import com.zerodhatech.models.HistoricalData;
import lombok.experimental.UtilityClass;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Utility helpers for working with candle intervals and technical indicators.
 */
@UtilityClass
public class CandleUtils {

    /**
     * Calculate the timestamp of the next five-minute candle open after the provided moment.
     *
     * @param now reference timestamp (timezone already applied)
     * @return ZonedDateTime representing the next candle open (strictly after {@code now})
     */
    public static ZonedDateTime nextFiveMinuteCandle(ZonedDateTime now) {
        if (now == null) {
            throw new IllegalArgumentException("now must not be null");
        }

        ZonedDateTime truncated = now.truncatedTo(ChronoUnit.MINUTES);
        int minute = truncated.getMinute();
        int remainder = minute % 5;
        int minutesToAdd = remainder == 0 ? 5 : (5 - remainder);

        ZonedDateTime candidate = truncated.plusMinutes(minutesToAdd);
        if (!candidate.isAfter(now)) {
            candidate = candidate.plusMinutes(5);
        }
        return candidate.withSecond(0).withNano(0);
    }

    /**
     * Calculate the duration between {@code now} and the next five-minute candle open.
     *
     * @param now reference timestamp (timezone already applied)
     * @return positive duration until the next candle open
     */
    public static Duration durationUntilNextFiveMinuteCandle(ZonedDateTime now) {
        ZonedDateTime next = nextFiveMinuteCandle(now);
        return Duration.between(now, next);
    }

    /**
     * Compute an ADX (Average Directional Index) series using Wilder's smoothing method.
     *
     * <p>Implements the standard Wilder's ADX algorithm:</p>
     * <ol>
     *   <li>True Range (TR), +DM, -DM from consecutive candles</li>
     *   <li>Wilder's smoothed TR, +DM, -DM → +DI, -DI → DX</li>
     *   <li>ADX = Wilder's smoothed DX</li>
     * </ol>
     *
     * @param candles historical candles (high, low, close)
     * @param period  smoothing period (typically 14)
     * @return array of ADX values (may be shorter than input due to warmup)
     */
    public static double[] computeADXSeries(List<HistoricalData> candles, int period) {
        int n = candles.size();
        if (n < period + 1) {
            return new double[0];
        }

        // Step 1: Raw TR, +DM, -DM (start from index 1 — need previous candle)
        int rawLen = n - 1;
        double[] tr = new double[rawLen];
        double[] plusDM = new double[rawLen];
        double[] minusDM = new double[rawLen];

        for (int i = 1; i < n; i++) {
            HistoricalData curr = candles.get(i);
            HistoricalData prev = candles.get(i - 1);

            double highLow = curr.high - curr.low;
            double highPrevClose = Math.abs(curr.high - prev.close);
            double lowPrevClose = Math.abs(curr.low - prev.close);
            tr[i - 1] = Math.max(highLow, Math.max(highPrevClose, lowPrevClose));

            double upMove = curr.high - prev.high;
            double downMove = prev.low - curr.low;

            plusDM[i - 1] = (upMove > downMove && upMove > 0) ? upMove : 0;
            minusDM[i - 1] = (downMove > upMove && downMove > 0) ? downMove : 0;
        }

        // Step 2: Wilder's smoothed TR, +DM, -DM
        if (rawLen < period) {
            return new double[0];
        }

        double smoothedTR = 0, smoothedPlusDM = 0, smoothedMinusDM = 0;
        for (int i = 0; i < period; i++) {
            smoothedTR += tr[i];
            smoothedPlusDM += plusDM[i];
            smoothedMinusDM += minusDM[i];
        }

        int dxCapacity = rawLen - period + 1;
        if (dxCapacity <= 0) {
            return new double[0];
        }
        double[] dx = new double[dxCapacity];

        double plusDI = (smoothedTR > 0) ? 100.0 * smoothedPlusDM / smoothedTR : 0;
        double minusDI = (smoothedTR > 0) ? 100.0 * smoothedMinusDM / smoothedTR : 0;
        double diSum = plusDI + minusDI;
        dx[0] = (diSum > 0) ? 100.0 * Math.abs(plusDI - minusDI) / diSum : 0;

        for (int i = period; i < rawLen; i++) {
            smoothedTR = smoothedTR - (smoothedTR / period) + tr[i];
            smoothedPlusDM = smoothedPlusDM - (smoothedPlusDM / period) + plusDM[i];
            smoothedMinusDM = smoothedMinusDM - (smoothedMinusDM / period) + minusDM[i];

            plusDI = (smoothedTR > 0) ? 100.0 * smoothedPlusDM / smoothedTR : 0;
            minusDI = (smoothedTR > 0) ? 100.0 * smoothedMinusDM / smoothedTR : 0;
            diSum = plusDI + minusDI;
            dx[i - period + 1] = (diSum > 0) ? 100.0 * Math.abs(plusDI - minusDI) / diSum : 0;
        }

        // Step 3: ADX = Wilder's smoothed DX
        if (dx.length < period) {
            return new double[]{dx[dx.length - 1]};
        }

        int adxLen = dx.length - period + 1;
        double[] adx = new double[adxLen];

        double adxSum = 0;
        for (int i = 0; i < period; i++) {
            adxSum += dx[i];
        }
        adx[0] = adxSum / period;

        for (int i = period; i < dx.length; i++) {
            adx[i - period + 1] = (adx[i - period] * (period - 1) + dx[i]) / period;
        }

        return adx;
    }
}
