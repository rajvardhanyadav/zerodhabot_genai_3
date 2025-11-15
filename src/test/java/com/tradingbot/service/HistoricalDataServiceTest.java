package com.tradingbot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

public class HistoricalDataServiceTest {

    private HistoricalDataService service;
    private Method parseMethod;

    @BeforeEach
    void setUp() throws Exception {
        TradingService tradingService = mock(TradingService.class);
        service = new HistoricalDataService(tradingService);
        parseMethod = HistoricalDataService.class.getDeclaredMethod("parseCandleTimestamp", String.class);
        parseMethod.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private Optional<ZonedDateTime> invokeParse(String ts) {
        try {
            return (Optional<ZonedDateTime>) parseMethod.invoke(service, ts);
        } catch (Exception e) {
            fail("Reflection invocation failed: " + e.getMessage());
            return Optional.empty();
        }
    }

    @Test
    void parsesLegacyFormatWithoutOffset() {
        Optional<ZonedDateTime> parsed = invokeParse("2025-11-14 09:15:00");
        assertTrue(parsed.isPresent(), "Legacy format should parse");
        assertEquals(ZoneId.of("Asia/Kolkata"), parsed.get().getZone());
        assertEquals(9, parsed.get().getHour());
        assertEquals(15, parsed.get().getMinute());
    }

    @Test
    void parsesOffsetWithoutColon() {
        Optional<ZonedDateTime> parsed = invokeParse("2025-11-14T15:23:00+0530");
        assertTrue(parsed.isPresent(), "Offset without colon should parse");
        assertEquals(ZoneId.of("Asia/Kolkata"), parsed.get().getZone(), "Normalized to IST zone");
        assertEquals(15, parsed.get().getHour());
        assertEquals(23, parsed.get().getMinute());
    }

    @Test
    void parsesOffsetWithColon() {
        Optional<ZonedDateTime> parsed = invokeParse("2025-11-14T15:23:00+05:30");
        assertTrue(parsed.isPresent(), "Offset with colon should parse");
        assertEquals(ZoneId.of("Asia/Kolkata"), parsed.get().getZone());
        assertEquals(15, parsed.get().getHour());
    }

    @Test
    void parsesOffsetWithMillisNoColon() {
        Optional<ZonedDateTime> parsed = invokeParse("2025-11-14T15:23:00.123+0530");
        assertTrue(parsed.isPresent(), "Offset millis without colon should parse");
        assertEquals(123_000_000, parsed.get().getNano(), "Millisecond precision retained");
    }

    @Test
    void parsesOffsetWithMillisColon() {
        Optional<ZonedDateTime> parsed = invokeParse("2025-11-14T15:23:00.456+05:30");
        assertTrue(parsed.isPresent(), "Offset millis with colon should parse");
        assertEquals(456_000_000, parsed.get().getNano());
    }

    @Test
    void returnsEmptyForInvalid() {
        Optional<ZonedDateTime> parsed = invokeParse("BAD-TIMESTAMP");
        assertTrue(parsed.isEmpty(), "Invalid timestamp should not parse");
    }

    @Test
    void allEquivalentInstantsMatch() {
        Optional<ZonedDateTime> a = invokeParse("2025-11-14T15:23:00+0530");
        Optional<ZonedDateTime> b = invokeParse("2025-11-14T15:23:00+05:30");
        Optional<ZonedDateTime> c = invokeParse("2025-11-14 15:23:00");
        assertTrue(a.isPresent() && b.isPresent() && c.isPresent(), "All formats should parse");
        assertEquals(a.get().toEpochSecond(), b.get().toEpochSecond(), "Colon vs no-colon offset epoch equality");
        assertEquals(a.get().toEpochSecond(), c.get().toEpochSecond(), "Legacy no-offset interpreted as IST should match offset forms");
    }
}

