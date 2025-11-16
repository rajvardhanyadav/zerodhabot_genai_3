package com.tradingbot.util;

import lombok.experimental.UtilityClass;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Utility helpers for working with candle intervals.
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
}

