package com.tradingbot.backtest.adapter;

import com.tradingbot.backtest.adapter.HistoricalCandleAdapter.SimulatedCandle;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Merges CE and PE candle streams into a single chronological timeline.
 * <p>
 * Produces a sorted list of {@link MergedTick} entries where each entry
 * contains the LTP for both legs at a given minute. If one leg has a candle
 * the other doesn't, the last known price is carried forward.
 */
@UtilityClass
@Slf4j
public class TickFeedMerger {

    /**
     * A single point-in-time snapshot of both legs' prices.
     *
     * @param timestamp minute timestamp
     * @param ceLtp CE close price (simulated LTP)
     * @param peLtp PE close price (simulated LTP)
     * @param ceToken CE instrument token
     * @param peToken PE instrument token
     */
    public record MergedTick(
            LocalDateTime timestamp,
            double ceLtp,
            double peLtp,
            long ceToken,
            long peToken
    ) {}

    /**
     * Merge CE and PE candle streams into a chronological tick feed.
     *
     * @param ceCandles CE candle data (from HistoricalCandleAdapter)
     * @param peCandles PE candle data (from HistoricalCandleAdapter)
     * @param ceToken   CE instrument token
     * @param peToken   PE instrument token
     * @return sorted list of MergedTick (ascending by timestamp)
     */
    public static List<MergedTick> merge(List<SimulatedCandle> ceCandles,
                                          List<SimulatedCandle> peCandles,
                                          long ceToken, long peToken) {
        // Index candles by timestamp for O(1) lookup
        Map<LocalDateTime, Double> ceByTime = new LinkedHashMap<>(ceCandles.size());
        for (SimulatedCandle c : ceCandles) {
            ceByTime.put(c.timestamp(), c.close());
        }

        Map<LocalDateTime, Double> peByTime = new LinkedHashMap<>(peCandles.size());
        for (SimulatedCandle c : peCandles) {
            peByTime.put(c.timestamp(), c.close());
        }

        // Collect all unique timestamps and sort
        TreeSet<LocalDateTime> allTimestamps = new TreeSet<>();
        allTimestamps.addAll(ceByTime.keySet());
        allTimestamps.addAll(peByTime.keySet());

        if (allTimestamps.isEmpty()) {
            log.warn("No candle data available for merging");
            return Collections.emptyList();
        }

        // Merge with carry-forward for missing prices
        List<MergedTick> merged = new ArrayList<>(allTimestamps.size());
        double lastCe = 0.0;
        double lastPe = 0.0;

        for (LocalDateTime ts : allTimestamps) {
            Double cePrice = ceByTime.get(ts);
            Double pePrice = peByTime.get(ts);

            if (cePrice != null) lastCe = cePrice;
            if (pePrice != null) lastPe = pePrice;

            // Only emit ticks once both legs have been seen at least once
            if (lastCe > 0.0 && lastPe > 0.0) {
                merged.add(new MergedTick(ts, lastCe, lastPe, ceToken, peToken));
            }
        }

        log.debug("Merged {} ticks from {} CE candles + {} PE candles",
                merged.size(), ceCandles.size(), peCandles.size());
        return merged;
    }
}

