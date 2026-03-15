package com.tradingbot.service.strategy;

import com.tradingbot.config.StrategyConfig;
import com.tradingbot.model.MarketStateEvent;
import com.tradingbot.model.StrategyCompletionReason;
import com.tradingbot.model.StrategyExecution;
import com.tradingbot.model.StrategyStatus;
import com.tradingbot.model.StrategyType;
import com.tradingbot.service.BotStatusService;
import com.tradingbot.service.StrategyService;
import com.tradingbot.util.StrategyConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.scheduling.TaskScheduler;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Test that StrategyRestartScheduler correctly registers pending restarts and
 * reacts to MarketStateEvents for the event-driven neutral market detection.
 */
class StrategyRestartSchedulerTest {

    @Mock
    private StrategyConfig strategyConfig;

    @Mock
    private StrategyService strategyService;

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private DailyPnlGateService dailyPnlGateService;

    @Mock
    private BotStatusService botStatusService;

    @Mock
    private NeutralMarketDetectorService neutralMarketDetectorService;

    private StrategyRestartScheduler scheduler;

    @BeforeEach
    void setUp() {
        try (AutoCloseable mocks = MockitoAnnotations.openMocks(this)) {
            scheduler = new StrategyRestartScheduler(strategyConfig, strategyService, taskScheduler,
                    dailyPnlGateService, botStatusService, neutralMarketDetectorService);
            // Pin clock to 10:00 AM IST (within market hours 09:15 - 15:10)
            // 2026-03-14T10:00:00+05:30 = 2026-03-14T04:30:00Z
            Instant marketHoursInstant = Instant.parse("2026-03-14T04:30:00Z");
            scheduler.setClock(Clock.fixed(marketHoursInstant, ZoneId.of("Asia/Kolkata")));
            // Default: no daily P&L gate breach
            when(dailyPnlGateService.getBreachReason(any())).thenReturn(Optional.empty());
            when(dailyPnlGateService.getDailyPnl(any())).thenReturn(BigDecimal.ZERO);
            // Default neutral market poll config
            when(strategyConfig.getNeutralMarketPollIntervalMs()).thenReturn(30000L);
            when(strategyConfig.getNeutralMarketBufferMs()).thenReturn(60000L);
            // Mock taskScheduler.schedule() to return a non-null ScheduledFuture
            ScheduledFuture<?> mockFuture = mock(ScheduledFuture.class);
            doReturn(mockFuture).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize mocks", e);
        }
    }

    // ==================== PENDING RESTART REGISTRATION TESTS ====================

    @Test
    void testPaperModeCompletionRegistersPendingRestartWhenEnabled() {
        // Given: Auto-restart enabled globally and for paper mode
        when(strategyConfig.isAutoRestartEnabled()).thenReturn(true);
        when(strategyConfig.isAutoRestartPaperEnabled()).thenReturn(true);
        when(strategyConfig.getMaxAutoRestarts()).thenReturn(10);

        // Paper execution that hit target
        StrategyExecution execution = createExecution("exec-1", "user-1", StrategyConstants.TRADING_MODE_PAPER);
        execution.setStatus(StrategyStatus.COMPLETED);
        execution.setCompletionReason(StrategyCompletionReason.TARGET_HIT);

        // When: Completion event arrives
        StrategyService.StrategyCompletionEvent event = new StrategyService.StrategyCompletionEvent(this, execution);
        scheduler.onStrategyCompletion(event);

        // Then: Execution should be registered as pending (no polling task scheduled)
        assertEquals(1, scheduler.getScheduledRestartsCount());
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void testPaperModeCompletionDoesNotRegisterWhenDisabled() {
        // Given: Auto-restart enabled globally but disabled for paper mode
        when(strategyConfig.isAutoRestartEnabled()).thenReturn(true);
        when(strategyConfig.isAutoRestartPaperEnabled()).thenReturn(false);

        // Paper execution that hit stoploss
        StrategyExecution execution = createExecution("exec-2", "user-2", StrategyConstants.TRADING_MODE_PAPER);
        execution.setStatus(StrategyStatus.COMPLETED);
        execution.setCompletionReason(StrategyCompletionReason.STOPLOSS_HIT);

        // When: Completion event arrives
        StrategyService.StrategyCompletionEvent event = new StrategyService.StrategyCompletionEvent(this, execution);
        scheduler.onStrategyCompletion(event);

        // Then: Nothing registered
        assertEquals(0, scheduler.getScheduledRestartsCount());
    }

    @Test
    void testLiveModeCompletionRegistersPendingRestartWhenEnabled() {
        // Given: Auto-restart enabled globally and for live mode
        when(strategyConfig.isAutoRestartEnabled()).thenReturn(true);
        when(strategyConfig.isAutoRestartLiveEnabled()).thenReturn(true);
        when(strategyConfig.getMaxAutoRestarts()).thenReturn(5);

        // Live execution that hit target
        StrategyExecution execution = createExecution("exec-3", "user-3", StrategyConstants.TRADING_MODE_LIVE);
        execution.setStatus(StrategyStatus.COMPLETED);
        execution.setCompletionReason(StrategyCompletionReason.TARGET_HIT);

        // When: Completion event arrives
        StrategyService.StrategyCompletionEvent event = new StrategyService.StrategyCompletionEvent(this, execution);
        scheduler.onStrategyCompletion(event);

        // Then: Execution should be registered as pending
        assertEquals(1, scheduler.getScheduledRestartsCount());
    }

    @Test
    void testManualStopDoesNotRegisterRestart() {
        // Given: Auto-restart enabled for paper
        when(strategyConfig.isAutoRestartEnabled()).thenReturn(true);
        when(strategyConfig.isAutoRestartPaperEnabled()).thenReturn(true);

        // Execution stopped manually
        StrategyExecution execution = createExecution("exec-4", "user-4", StrategyConstants.TRADING_MODE_PAPER);
        execution.setStatus(StrategyStatus.COMPLETED);
        execution.setCompletionReason(StrategyCompletionReason.MANUAL_STOP);

        // When: Completion event arrives
        StrategyService.StrategyCompletionEvent event = new StrategyService.StrategyCompletionEvent(this, execution);
        scheduler.onStrategyCompletion(event);

        // Then: Nothing registered (manual stop not eligible)
        assertEquals(0, scheduler.getScheduledRestartsCount());
    }

    @Test
    void testMaxRestartLimitPreventsRegistration() {
        // Given: Max restarts = 2, execution already restarted 2 times
        when(strategyConfig.isAutoRestartEnabled()).thenReturn(true);
        when(strategyConfig.isAutoRestartPaperEnabled()).thenReturn(true);
        when(strategyConfig.getMaxAutoRestarts()).thenReturn(2);

        StrategyExecution execution = createExecution("exec-5", "user-5", StrategyConstants.TRADING_MODE_PAPER);
        execution.setStatus(StrategyStatus.COMPLETED);
        execution.setCompletionReason(StrategyCompletionReason.TARGET_HIT);
        execution.setAutoRestartCount(2); // Already at limit

        // When: Completion event arrives
        StrategyService.StrategyCompletionEvent event = new StrategyService.StrategyCompletionEvent(this, execution);
        scheduler.onStrategyCompletion(event);

        // Then: Nothing registered (limit reached)
        assertEquals(0, scheduler.getScheduledRestartsCount());
    }

    // ==================== DAILY P&L GATE TESTS ====================

    @Test
    void testDailyPnlGateBlocksRestartForSellATMStraddle() {
        // Given: Auto-restart enabled, daily profit limit breached
        when(strategyConfig.isAutoRestartEnabled()).thenReturn(true);
        when(strategyConfig.isAutoRestartPaperEnabled()).thenReturn(true);
        when(strategyConfig.getMaxAutoRestarts()).thenReturn(0);

        StrategyExecution execution = createExecution("exec-6", "user-6", StrategyConstants.TRADING_MODE_PAPER);
        execution.setStrategyType(StrategyType.SELL_ATM_STRADDLE);
        execution.setStatus(StrategyStatus.COMPLETED);
        execution.setCompletionReason(StrategyCompletionReason.TARGET_HIT);

        // Daily P&L gate is breached
        when(dailyPnlGateService.getBreachReason("user-6"))
                .thenReturn(Optional.of(StrategyCompletionReason.DAY_PROFIT_LIMIT_HIT));
        when(dailyPnlGateService.getDailyPnl("user-6")).thenReturn(BigDecimal.valueOf(5000));

        // When
        scheduler.onStrategyCompletion(new StrategyService.StrategyCompletionEvent(this, execution));

        // Then: Restart should NOT be registered and bot should be stopped
        assertEquals(0, scheduler.getScheduledRestartsCount());
        verify(botStatusService, times(1)).markStopped();
    }

    @Test
    void testDailyPnlGateDoesNotBlockATMStraddle() {
        // Given: Auto-restart enabled, daily profit limit breached BUT strategy is ATM_STRADDLE (not SELL)
        when(strategyConfig.isAutoRestartEnabled()).thenReturn(true);
        when(strategyConfig.isAutoRestartPaperEnabled()).thenReturn(true);
        when(strategyConfig.getMaxAutoRestarts()).thenReturn(0);

        StrategyExecution execution = createExecution("exec-7", "user-7", StrategyConstants.TRADING_MODE_PAPER);
        execution.setStrategyType(StrategyType.ATM_STRADDLE); // Not SELL_ATM_STRADDLE
        execution.setStatus(StrategyStatus.COMPLETED);
        execution.setCompletionReason(StrategyCompletionReason.TARGET_HIT);

        // Daily P&L gate is breached but should be ignored for ATM_STRADDLE
        when(dailyPnlGateService.getBreachReason("user-7"))
                .thenReturn(Optional.of(StrategyCompletionReason.DAY_PROFIT_LIMIT_HIT));

        // When
        scheduler.onStrategyCompletion(new StrategyService.StrategyCompletionEvent(this, execution));

        // Then: Gate should NOT block ATM_STRADDLE — bot should NOT be stopped.
        verify(botStatusService, never()).markStopped();
    }

    @Test
    void testDailyLossLimitBlocksRestart() {
        // Given: Auto-restart enabled, daily loss limit breached
        when(strategyConfig.isAutoRestartEnabled()).thenReturn(true);
        when(strategyConfig.isAutoRestartPaperEnabled()).thenReturn(true);
        when(strategyConfig.getMaxAutoRestarts()).thenReturn(0);

        StrategyExecution execution = createExecution("exec-8", "user-8", StrategyConstants.TRADING_MODE_PAPER);
        execution.setStrategyType(StrategyType.SELL_ATM_STRADDLE);
        execution.setStatus(StrategyStatus.COMPLETED);
        execution.setCompletionReason(StrategyCompletionReason.STOPLOSS_HIT);

        // Daily P&L gate: loss limit breached
        when(dailyPnlGateService.getBreachReason("user-8"))
                .thenReturn(Optional.of(StrategyCompletionReason.DAY_LOSS_LIMIT_HIT));
        when(dailyPnlGateService.getDailyPnl("user-8")).thenReturn(BigDecimal.valueOf(-3000));

        // When
        scheduler.onStrategyCompletion(new StrategyService.StrategyCompletionEvent(this, execution));

        // Then: Restart blocked, bot stopped
        assertEquals(0, scheduler.getScheduledRestartsCount());
        verify(botStatusService, times(1)).markStopped();
    }

    @Test
    void testNoPnlGateBreachAllowsRestart() {
        // Given: Auto-restart enabled, no daily P&L breach
        when(strategyConfig.isAutoRestartEnabled()).thenReturn(true);
        when(strategyConfig.isAutoRestartPaperEnabled()).thenReturn(true);
        when(strategyConfig.getMaxAutoRestarts()).thenReturn(0);

        StrategyExecution execution = createExecution("exec-9", "user-9", StrategyConstants.TRADING_MODE_PAPER);
        execution.setStrategyType(StrategyType.SELL_ATM_STRADDLE);
        execution.setStatus(StrategyStatus.COMPLETED);
        execution.setCompletionReason(StrategyCompletionReason.TARGET_HIT);

        // No breach
        when(dailyPnlGateService.getBreachReason("user-9")).thenReturn(Optional.empty());

        // When
        scheduler.onStrategyCompletion(new StrategyService.StrategyCompletionEvent(this, execution));

        // Then: Gate should NOT block — bot should NOT be stopped. Restart is registered.
        verify(botStatusService, never()).markStopped();
        assertEquals(1, scheduler.getScheduledRestartsCount());
    }

    // ==================== MARKET STATE EVENT TESTS ====================

    @Test
    void testNeutralMarketEventTriggersBufferedExecution() {
        // Given: A pending restart is registered
        when(strategyConfig.isAutoRestartEnabled()).thenReturn(true);
        when(strategyConfig.isAutoRestartPaperEnabled()).thenReturn(true);
        when(strategyConfig.getMaxAutoRestarts()).thenReturn(0);

        StrategyExecution execution = createExecution("exec-10", "user-10", StrategyConstants.TRADING_MODE_PAPER);
        execution.setStatus(StrategyStatus.COMPLETED);
        execution.setCompletionReason(StrategyCompletionReason.TARGET_HIT);

        scheduler.onStrategyCompletion(new StrategyService.StrategyCompletionEvent(this, execution));
        assertEquals(1, scheduler.getScheduledRestartsCount());

        // When: A neutral market event is published for the matching instrument
        MarketStateEvent neutralEvent = new MarketStateEvent(
                "NIFTY", true, 8, 10, null, Instant.now());
        scheduler.onMarketStateEvent(neutralEvent);

        // Then: Pending restart consumed, buffered execution scheduled via taskScheduler
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void testNonNeutralMarketEventDoesNotTriggerExecution() {
        // Given: A pending restart is registered
        when(strategyConfig.isAutoRestartEnabled()).thenReturn(true);
        when(strategyConfig.isAutoRestartPaperEnabled()).thenReturn(true);
        when(strategyConfig.getMaxAutoRestarts()).thenReturn(0);

        StrategyExecution execution = createExecution("exec-11", "user-11", StrategyConstants.TRADING_MODE_PAPER);
        execution.setStatus(StrategyStatus.COMPLETED);
        execution.setCompletionReason(StrategyCompletionReason.TARGET_HIT);

        scheduler.onStrategyCompletion(new StrategyService.StrategyCompletionEvent(this, execution));
        assertEquals(1, scheduler.getScheduledRestartsCount());

        // When: A non-neutral market event is published
        MarketStateEvent nonNeutralEvent = new MarketStateEvent(
                "NIFTY", false, 4, 10, null, Instant.now());
        scheduler.onMarketStateEvent(nonNeutralEvent);

        // Then: No task scheduled, pending restart still waiting
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
        assertEquals(1, scheduler.getScheduledRestartsCount());
    }

    @Test
    void testNeutralEventForDifferentInstrumentDoesNotTrigger() {
        // Given: A pending restart for NIFTY
        when(strategyConfig.isAutoRestartEnabled()).thenReturn(true);
        when(strategyConfig.isAutoRestartPaperEnabled()).thenReturn(true);
        when(strategyConfig.getMaxAutoRestarts()).thenReturn(0);

        StrategyExecution execution = createExecution("exec-12", "user-12", StrategyConstants.TRADING_MODE_PAPER);
        execution.setInstrumentType("NIFTY");
        execution.setStatus(StrategyStatus.COMPLETED);
        execution.setCompletionReason(StrategyCompletionReason.TARGET_HIT);

        scheduler.onStrategyCompletion(new StrategyService.StrategyCompletionEvent(this, execution));
        assertEquals(1, scheduler.getScheduledRestartsCount());

        // When: A neutral event for BANKNIFTY (different instrument)
        MarketStateEvent bankNiftyEvent = new MarketStateEvent(
                "BANKNIFTY", true, 8, 10, null, Instant.now());
        scheduler.onMarketStateEvent(bankNiftyEvent);

        // Then: NIFTY pending restart not consumed
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
        assertEquals(1, scheduler.getScheduledRestartsCount());
    }

    @Test
    void testCancelScheduledRestartRemovesPending() {
        // Given: A pending restart is registered
        when(strategyConfig.isAutoRestartEnabled()).thenReturn(true);
        when(strategyConfig.isAutoRestartPaperEnabled()).thenReturn(true);
        when(strategyConfig.getMaxAutoRestarts()).thenReturn(0);

        StrategyExecution execution = createExecution("exec-13", "user-13", StrategyConstants.TRADING_MODE_PAPER);
        execution.setStatus(StrategyStatus.COMPLETED);
        execution.setCompletionReason(StrategyCompletionReason.TARGET_HIT);

        scheduler.onStrategyCompletion(new StrategyService.StrategyCompletionEvent(this, execution));
        assertEquals(1, scheduler.getScheduledRestartsCount());

        // When: Cancel the pending restart
        boolean cancelled = scheduler.cancelScheduledRestart("exec-13");

        // Then: Pending restart removed
        assertTrue(cancelled);
        assertEquals(0, scheduler.getScheduledRestartsCount());
    }

    @Test
    void testCancelAllClearsPendingAndScheduled() {
        // Given: Two pending restarts registered
        when(strategyConfig.isAutoRestartEnabled()).thenReturn(true);
        when(strategyConfig.isAutoRestartPaperEnabled()).thenReturn(true);
        when(strategyConfig.getMaxAutoRestarts()).thenReturn(0);

        StrategyExecution exec1 = createExecution("exec-14", "user-14", StrategyConstants.TRADING_MODE_PAPER);
        exec1.setStatus(StrategyStatus.COMPLETED);
        exec1.setCompletionReason(StrategyCompletionReason.TARGET_HIT);

        StrategyExecution exec2 = createExecution("exec-15", "user-15", StrategyConstants.TRADING_MODE_PAPER);
        exec2.setStatus(StrategyStatus.COMPLETED);
        exec2.setCompletionReason(StrategyCompletionReason.STOPLOSS_HIT);

        scheduler.onStrategyCompletion(new StrategyService.StrategyCompletionEvent(this, exec1));
        scheduler.onStrategyCompletion(new StrategyService.StrategyCompletionEvent(this, exec2));
        assertEquals(2, scheduler.getScheduledRestartsCount());

        // When: Cancel all
        int cancelledCount = scheduler.cancelAllScheduledRestarts();

        // Then: All cleared
        assertEquals(2, cancelledCount);
        assertEquals(0, scheduler.getScheduledRestartsCount());
    }

    private StrategyExecution createExecution(String executionId, String userId, String tradingMode) {
        StrategyExecution execution = new StrategyExecution();
        execution.setExecutionId(executionId);
        execution.setUserId(userId);
        execution.setTradingMode(tradingMode);
        execution.setStrategyType(StrategyType.ATM_STRADDLE);
        execution.setInstrumentType("NIFTY");
        execution.setExpiry("2025-11-20");
        execution.setAutoRestartCount(0);
        execution.setRootExecutionId(executionId);
        return execution;
    }
}

