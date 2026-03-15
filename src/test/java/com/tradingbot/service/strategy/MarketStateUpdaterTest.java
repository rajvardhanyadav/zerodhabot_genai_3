package com.tradingbot.service.strategy;

import com.tradingbot.config.NeutralMarketConfig;
import com.tradingbot.model.MarketStateEvent;
import com.tradingbot.service.strategy.NeutralMarketDetectorService.NeutralMarketResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link MarketStateUpdater} — the scheduled component that
 * evaluates market neutrality and publishes {@link MarketStateEvent}s.
 */
class MarketStateUpdaterTest {

    @Mock
    private NeutralMarketDetectorService neutralMarketDetectorService;

    @Mock
    private NeutralMarketConfig neutralMarketConfig;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private MarketStateUpdater updater;

    @BeforeEach
    void setUp() {
        try (AutoCloseable mocks = MockitoAnnotations.openMocks(this)) {
            updater = new MarketStateUpdater(neutralMarketDetectorService, neutralMarketConfig, eventPublisher);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize mocks", e);
        }
    }

    @Test
    void testEvaluateAndPublish_WhenDisabled_NoEventsPublished() {
        // Given: Neutral market filter is disabled
        when(neutralMarketConfig.isEnabled()).thenReturn(false);

        // When
        updater.evaluateAndPublish();

        // Then: No evaluation calls, no events
        verify(neutralMarketDetectorService, never()).evaluate(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void testEvaluateAndPublish_WhenEnabled_PublishesEventsForBothInstruments() {
        // Given: Neutral market filter is enabled
        when(neutralMarketConfig.isEnabled()).thenReturn(true);

        NeutralMarketResult niftyResult = new NeutralMarketResult(
                true, 8, 10, 7, Collections.emptyList(), "test-summary", Instant.now());
        NeutralMarketResult bankNiftyResult = new NeutralMarketResult(
                false, 4, 10, 7, Collections.emptyList(), "test-summary", Instant.now());

        when(neutralMarketDetectorService.evaluate("NIFTY")).thenReturn(niftyResult);
        when(neutralMarketDetectorService.evaluate("BANKNIFTY")).thenReturn(bankNiftyResult);

        // Spy on the updater to override market hours check
        MarketStateUpdater spyUpdater = spy(updater);
        doReturn(true).when(spyUpdater).isWithinMarketHours();

        // When
        spyUpdater.evaluateAndPublish();

        // Then: Two events published
        ArgumentCaptor<MarketStateEvent> eventCaptor = ArgumentCaptor.forClass(MarketStateEvent.class);
        verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());

        List<MarketStateEvent> events = eventCaptor.getAllValues();

        // NIFTY event
        MarketStateEvent niftyEvent = events.get(0);
        assertEquals("NIFTY", niftyEvent.instrumentType());
        assertTrue(niftyEvent.neutral());
        assertEquals(8, niftyEvent.score());

        // BANKNIFTY event
        MarketStateEvent bankNiftyEvent = events.get(1);
        assertEquals("BANKNIFTY", bankNiftyEvent.instrumentType());
        assertFalse(bankNiftyEvent.neutral());
        assertEquals(4, bankNiftyEvent.score());
    }

    @Test
    void testEvaluateAndPublish_WhenEvaluationThrows_ContinuesToNextInstrument() {
        // Given: Neutral market filter is enabled
        when(neutralMarketConfig.isEnabled()).thenReturn(true);

        // NIFTY evaluation throws
        when(neutralMarketDetectorService.evaluate("NIFTY"))
                .thenThrow(new RuntimeException("Kite API timeout"));

        NeutralMarketResult bankNiftyResult = new NeutralMarketResult(
                true, 8, 10, 7, Collections.emptyList(), "test-summary", Instant.now());
        when(neutralMarketDetectorService.evaluate("BANKNIFTY")).thenReturn(bankNiftyResult);

        MarketStateUpdater spyUpdater = spy(updater);
        doReturn(true).when(spyUpdater).isWithinMarketHours();

        // When
        spyUpdater.evaluateAndPublish();

        // Then: Only BANKNIFTY event published (NIFTY failed gracefully)
        ArgumentCaptor<MarketStateEvent> eventCaptor = ArgumentCaptor.forClass(MarketStateEvent.class);
        verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());

        MarketStateEvent event = eventCaptor.getValue();
        assertEquals("BANKNIFTY", event.instrumentType());
        assertTrue(event.neutral());
    }

    @Test
    void testEvaluateAndPublish_OutsideMarketHours_NoEventsPublished() {
        // Given: Neutral market filter is enabled, but outside market hours
        when(neutralMarketConfig.isEnabled()).thenReturn(true);

        MarketStateUpdater spyUpdater = spy(updater);
        doReturn(false).when(spyUpdater).isWithinMarketHours();

        // When
        spyUpdater.evaluateAndPublish();

        // Then: No evaluation, no events
        verify(neutralMarketDetectorService, never()).evaluate(any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}

