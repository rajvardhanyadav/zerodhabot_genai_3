package com.tradingbot.service.strategy;

import com.tradingbot.config.MarketDataEngineConfig;
import com.tradingbot.config.NeutralMarketV3Config;
import com.tradingbot.model.MarketStateEvent;
import com.tradingbot.model.NeutralMarketEvaluation;
import com.tradingbot.model.NeutralMarketResultV3;
import com.tradingbot.model.Regime;
import com.tradingbot.model.BreakoutRisk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import com.tradingbot.service.session.UserSessionManager;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link MarketStateUpdater} — the scheduled component that
 * evaluates market neutrality and publishes {@link MarketStateEvent}s.
 */
class MarketStateUpdaterTest {

    @Mock
    private NeutralMarketDetector neutralMarketDetectorService;

    @Mock
    private NeutralMarketV3Config neutralMarketV3Config;

    @Mock
    private MarketDataEngineConfig marketDataEngineConfig;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private UserSessionManager userSessionManager;

    private MarketStateUpdater updater;

    @BeforeEach
    void setUp() {
        try (AutoCloseable mocks = MockitoAnnotations.openMocks(this)) {
            when(marketDataEngineConfig.getSupportedInstrumentsArray()).thenReturn(new String[]{"NIFTY"});
            updater = new MarketStateUpdater(neutralMarketDetectorService, neutralMarketV3Config,
                    marketDataEngineConfig, eventPublisher, userSessionManager);
            // Default: at least one active user session
            when(userSessionManager.getActiveUserIds()).thenReturn(Set.of("TEST_USER"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize mocks", e);
        }
    }

    @Test
    void testEvaluateAndPublish_WhenDisabled_NoEventsPublished() {
        // Given: Neutral market filter is disabled
        when(neutralMarketV3Config.isEnabled()).thenReturn(false);

        // When
        updater.evaluateAndPublish();

        // Then: No evaluation calls, no events
        verify(neutralMarketDetectorService, never()).evaluate(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void testEvaluateAndPublish_WhenEnabled_PublishesEventForNifty() {
        // Given: Neutral market filter is enabled
        when(neutralMarketV3Config.isEnabled()).thenReturn(true);

        NeutralMarketResultV3 niftyResult = new NeutralMarketResultV3(
                true, 7, 3, 10, 0.67,
                Regime.STRONG_NEUTRAL, BreakoutRisk.LOW,
                true, Collections.emptyMap(), "test-summary", Instant.now());

        when(neutralMarketDetectorService.evaluate("NIFTY")).thenReturn(niftyResult);

        // Spy on the updater to override market hours check
        MarketStateUpdater spyUpdater = spy(updater);
        doReturn(true).when(spyUpdater).isWithinMarketHours();

        // When
        spyUpdater.evaluateAndPublish();

        // Then: One event published for NIFTY
        ArgumentCaptor<MarketStateEvent> eventCaptor = ArgumentCaptor.forClass(MarketStateEvent.class);
        verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());

        MarketStateEvent niftyEvent = eventCaptor.getValue();
        assertEquals("NIFTY", niftyEvent.instrumentType());
        assertTrue(niftyEvent.neutral());
        assertEquals(10, niftyEvent.score());
    }

    @Test
    void testEvaluateAndPublish_WhenEvaluationThrows_NoEventsPublished() {
        // Given: Neutral market filter is enabled
        when(neutralMarketV3Config.isEnabled()).thenReturn(true);

        // NIFTY evaluation throws
        when(neutralMarketDetectorService.evaluate("NIFTY"))
                .thenThrow(new RuntimeException("Kite API timeout"));

        MarketStateUpdater spyUpdater = spy(updater);
        doReturn(true).when(spyUpdater).isWithinMarketHours();

        // When
        spyUpdater.evaluateAndPublish();

        // Then: No events published (NIFTY failed gracefully)
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void testEvaluateAndPublish_OutsideMarketHours_NoEventsPublished() {
        // Given: Neutral market filter is enabled, but outside market hours
        when(neutralMarketV3Config.isEnabled()).thenReturn(true);

        MarketStateUpdater spyUpdater = spy(updater);
        doReturn(false).when(spyUpdater).isWithinMarketHours();

        // When
        spyUpdater.evaluateAndPublish();

        // Then: No evaluation, no events
        verify(neutralMarketDetectorService, never()).evaluate(any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
