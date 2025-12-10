package com.tradingbot.service.strategy.monitoring;

import com.tradingbot.config.KiteConfig;
import com.tradingbot.config.PersistenceConfig;
import com.tradingbot.service.persistence.TradePersistenceService;
import com.tradingbot.service.session.UserSessionManager;
import com.tradingbot.util.CurrentUserContext;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Tick;
import com.zerodhatech.ticker.KiteTicker;
import com.zerodhatech.ticker.OnError;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * WebSocket service for real-time price updates.
 * Refactored to support per-user WebSocket connections and subscriptions.
 */
@Service
@Slf4j
@Getter
public class WebSocketService implements DisposableBean {

    private final UserSessionManager sessionManager;
    private final KiteConfig kiteConfig;
    private final PersistenceConfig persistenceConfig;

    @Autowired
    @Lazy
    private TradePersistenceService tradePersistenceService;

    public WebSocketService(UserSessionManager sessionManager, KiteConfig kiteConfig,
                             PersistenceConfig persistenceConfig) {
        this.sessionManager = sessionManager;
        this.kiteConfig = kiteConfig;
        this.persistenceConfig = persistenceConfig;
    }


    /** Per-user WebSocket context */
    private static class UserWSContext {
        final String userId;
        KiteTicker ticker;
        final Map<String, PositionMonitor> activeMonitors = new ConcurrentHashMap<>();
        final Map<Long, Set<String>> instrumentToExecutions = new ConcurrentHashMap<>();
        final AtomicBoolean isConnected = new AtomicBoolean(false);
        final AtomicBoolean isConnecting = new AtomicBoolean(false);
        final ReentrantLock connectionLock = new ReentrantLock();
        ScheduledExecutorService reconnectScheduler;

        UserWSContext(String userId) {
            this.userId = userId;
        }
    }

    private final Map<String, UserWSContext> contexts = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("WebSocketService initialized.");
    }

    // Helper to get or create the per-user context
    private UserWSContext ctx() {
        String userId = CurrentUserContext.getRequiredUserId();
        return contexts.computeIfAbsent(userId, id -> {
            UserWSContext c = new UserWSContext(id);
            c.reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ws-reconnect-" + id);
                t.setDaemon(true);
                return t;
            });
            return c;
        });
    }

    private KiteConnect currentKC() {
        return sessionManager.getRequiredKiteForCurrentUser();
    }

    // Public API (implicitly per-user)

    public boolean isWebSocketConnected() {
        return ctx().isConnected.get();
    }

    // Backward-compatible alias used by existing strategy code
    public boolean isConnected() {
        return isWebSocketConnected();
    }

    public int getActiveMonitorsCount() {
        return ctx().activeMonitors.size();
    }


    /** Connect WebSocket for current user (idempotent) */
    public void connect() {
        UserWSContext c = ctx();
        tryConnect(c);
    }

    private void tryConnect(UserWSContext c) {
        if (c.isConnected.get() || c.isConnecting.get()) {
            log.debug("[user={}] Connect ignored: already connected or connecting.", c.userId);
            return;
        }
        if (c.connectionLock.tryLock()) {
            try {
                if (!c.isConnecting.compareAndSet(false, true)) {
                    return;
                }
                log.info("[user={}] Establishing WebSocket connection...", c.userId);
                initializeAndConnectTicker(c);
            } finally {
                c.connectionLock.unlock();
            }
        } else {
            log.warn("[user={}] Connection attempt already in progress.", c.userId);
        }
    }

    public void disconnect() {
        UserWSContext c = ctx();
        disconnect(c);
    }

    private void disconnect(UserWSContext c) {
        c.connectionLock.lock();
        try {
            if (c.ticker != null && c.isConnected.get()) {
                log.info("[user={}] Disconnecting WebSocket.", c.userId);
                try { c.ticker.disconnect(); } catch (Exception ignore) {}
                c.isConnected.set(false);
                c.isConnecting.set(false);
            }
        } finally {
            c.connectionLock.unlock();
        }
    }

    /** Start monitoring for current user */
    public void startMonitoring(String executionId, PositionMonitor monitor) {
        UserWSContext c = ctx();
        if (c.activeMonitors.putIfAbsent(executionId, monitor) != null) {
            log.warn("[user={}] Monitoring already active for {}", c.userId, executionId);
            return;
        }
        List<Long> tokensToSubscribe = new ArrayList<>();
        for (PositionMonitor.LegMonitor leg : monitor.getLegs()) {
            long token = leg.getInstrumentToken();
            c.instrumentToExecutions.computeIfAbsent(token, k -> new CopyOnWriteArraySet<>()).add(executionId);
            tokensToSubscribe.add(token);
        }
        if (!tokensToSubscribe.isEmpty()) {
            subscribe(c, tokensToSubscribe);
        }
        log.info("[user={}] Started monitoring {} (legs={})", c.userId, executionId, monitor.getLegs().size());
    }

    /** Stop monitoring for current user */
    public void stopMonitoring(String executionId) {
        UserWSContext c = ctx();
        PositionMonitor monitor = c.activeMonitors.remove(executionId);
        if (monitor == null) {
            log.warn("[user={}] No active monitor for {}", c.userId, executionId);
            return;
        }
        monitor.stop();
        List<Long> tokensToUnsubscribe = new ArrayList<>();
        for (PositionMonitor.LegMonitor leg : monitor.getLegs()) {
            long token = leg.getInstrumentToken();
            Set<String> executions = c.instrumentToExecutions.get(token);
            if (executions != null) {
                executions.remove(executionId);
                if (executions.isEmpty()) {
                    c.instrumentToExecutions.remove(token);
                    tokensToUnsubscribe.add(token);
                }
            }
        }
        if (!tokensToUnsubscribe.isEmpty()) {
            unsubscribe(c, tokensToUnsubscribe);
        }
        log.info("[user={}] Stopped monitoring {}", c.userId, executionId);
    }

    // Historical replay helpers (per-user)
    public Optional<PositionMonitor> getMonitor(String executionId) {
        UserWSContext c = ctx();
        return Optional.ofNullable(c.activeMonitors.get(executionId));
    }

    public void feedTicks(String executionId, ArrayList<Tick> ticks) {
        UserWSContext c = ctx();
        PositionMonitor monitor = c.activeMonitors.get(executionId);
        if (monitor != null && monitor.isActive()) {
            monitor.updatePriceWithDifferenceCheck(ticks);
        }
    }

    /** Quick validation of access token for current user */
    public boolean isAccessTokenValid() {
        try {
            KiteConnect kc = currentKC();
            kc.getProfile();
            return true;
        } catch (KiteException | IOException | IllegalStateException e) {
            return false;
        }
    }

    // Internal helpers

    private void initializeAndConnectTicker(UserWSContext c) {
        try {
            KiteConnect kc = currentKC();
            String accessToken = kc.getAccessToken();
            if (accessToken == null || accessToken.isEmpty()) {
                throw new IllegalStateException("Access token not available. Please login first via /api/auth/session");
            }
            // Validate token explicitly and map to IllegalStateException for uniform handling
            try {
                kc.getProfile();
            } catch (KiteException | IOException e) {
                throw new IllegalStateException("Invalid or expired access token. Please authenticate again.", e);
            }

            setupTicker(c, accessToken);
            c.ticker.connect();
            log.info("[user={}] WebSocket connection initiated.", c.userId);
        } catch (IllegalStateException e) {
            log.error("[user={}] Connection failed pre-check: {}", c.userId, e.getMessage());
            c.isConnecting.set(false);
        } catch (Exception e) {
            log.error("[user={}] Fatal error during WebSocket initialization: {}", c.userId, e.getMessage(), e);
            c.isConnecting.set(false);
            scheduleReconnection(c, 1);
        }
    }

    private void setupTicker(UserWSContext c, String accessToken) {
        c.ticker = new KiteTicker(accessToken, kiteConfig.getApiKey());

        c.ticker.setOnConnectedListener(() -> onConnected(c));
        c.ticker.setOnDisconnectedListener(() -> onDisconnected(c));

        c.ticker.setOnErrorListener(new OnError() {
            @Override
            public void onError(Exception e) { handleError(c, e); }
            @Override
            public void onError(KiteException e) { handleError(c, e); }
            @Override
            public void onError(String message) { log.error("[user={}] WebSocket error: {}", c.userId, message); }
        });

        c.ticker.setOnTickerArrivalListener(ticks -> processTicks(c, ticks));

        c.ticker.setTryReconnection(false); // manual reconnection
    }

    private void onConnected(UserWSContext c) {
        c.isConnected.set(true);
        c.isConnecting.set(false);
        log.info("[user={}] WebSocket connected.", c.userId);
        resubscribeAll(c);

        // Persist connection event
        persistWebSocketEvent(c.userId, "CONNECTED", "WebSocket connection established",
                c.instrumentToExecutions.size(), null, null, null, null);
    }

    private void onDisconnected(UserWSContext c) {
        c.isConnected.set(false);
        c.isConnecting.set(false);
        log.warn("[user={}] WebSocket disconnected. Scheduling reconnect...", c.userId);

        // Persist disconnection event
        persistWebSocketEvent(c.userId, "DISCONNECTED", "WebSocket connection lost",
                c.instrumentToExecutions.size(), null, null, null, null);

        scheduleReconnection(c, 1);
    }

    private void handleError(UserWSContext c, Throwable e) {
        log.error("[user={}] WebSocket error", c.userId, e);
        c.isConnected.set(false);
        c.isConnecting.set(false);

        String errorCode = null;
        if (e instanceof KiteException ke) {
            errorCode = String.valueOf(ke.code);
            if (ke.code == 1001 || ke.code == 1009) {
                log.error("[user={}] Critical token error. Manual re-login required.", c.userId);

                // Persist critical error event
                persistWebSocketEvent(c.userId, "ERROR", "Critical token error - re-login required",
                        null, null, e.getMessage(), errorCode, null);
                return;
            }
        }

        // Persist error event
        persistWebSocketEvent(c.userId, "ERROR", "WebSocket error occurred",
                null, null, e.getMessage(), errorCode, null);

        scheduleReconnection(c, 1);
    }

    /**
     * Helper method to persist WebSocket events
     */
    private void persistWebSocketEvent(String userId, String eventType, String details,
                                         Integer subscribedTokenCount, Integer reconnectAttempt,
                                         String errorMessage, String errorCode, Long latencyMs) {
        if (persistenceConfig == null || !persistenceConfig.isEnabled() || tradePersistenceService == null) {
            return;
        }
        try {
            tradePersistenceService.persistWebSocketEventAsync(
                    userId, eventType, details, subscribedTokenCount,
                    reconnectAttempt, errorMessage, errorCode, latencyMs);
        } catch (Exception ex) {
            log.trace("Failed to persist WebSocket event: {}", ex.getMessage());
        }
    }

    private void scheduleReconnection(UserWSContext c, int attempt) {
        if (attempt > 10) {
            log.error("[user={}] Exceeded max reconnection attempts.", c.userId);
            return;
        }
        if (c.isConnecting.get() || c.isConnected.get()) {
            return;
        }
        long delay = (long) (5 * Math.pow(2, attempt - 1));
        log.info("[user={}] Scheduling reconnection attempt {} in {}s", c.userId, attempt, delay);
        c.reconnectScheduler.schedule(() -> {
            log.info("[user={}] Executing reconnection attempt #{}", c.userId, attempt);
            tryConnect(c);
        }, delay, TimeUnit.SECONDS);
    }

    private void subscribe(UserWSContext c, List<Long> tokens) {
        if (!c.isConnected.get()) {
            log.warn("[user={}] Not connected. Attempting to connect before subscribing.", c.userId);
            tryConnect(c);
            return;
        }
        try {
            ArrayList<Long> tokenList = new ArrayList<>(tokens);
            c.ticker.subscribe(tokenList);
            // Use modeFull for faster tick processing - LTP is pre-parsed, no additional parsing needed
            // modeLTP requires parsing the tick structure; modeFull provides direct access
            c.ticker.setMode(tokenList, KiteTicker.modeFull);
            log.info("[user={}] Subscribed to {} instruments in FULL mode.", c.userId, tokens.size());
        } catch (Exception e) {
            log.error("[user={}] Error subscribing: {}", c.userId, e.getMessage(), e);
        }
    }

    private void unsubscribe(UserWSContext c, List<Long> tokens) {
        if (!c.isConnected.get() || c.ticker == null) {
            log.warn("[user={}] Cannot unsubscribe - not connected.", c.userId);
            return;
        }
        try {
            c.ticker.unsubscribe(new ArrayList<>(tokens));
            log.info("[user={}] Unsubscribed from {} instruments.", c.userId, tokens.size());
        } catch (Exception e) {
            log.error("[user={}] Error unsubscribing: {}", c.userId, e.getMessage(), e);
        }
    }

    /**
     * Process incoming ticks from WebSocket.
     * HFT Critical Path - this method is called on every tick batch from WebSocket.
     *
     * Optimizations:
     * - Early exit checks to avoid unnecessary processing
     * - Indexed loop to avoid iterator allocation
     * - Lazy HashSet allocation only when needed
     * - Single pass through ticks to collect all monitors
     * - Batch update to monitors with full tick array
     */
    private void processTicks(UserWSContext c, ArrayList<Tick> ticks) {
        // HFT: Ultra-fast early exit checks
        if (ticks == null) {
            return;
        }
        final int tickCount = ticks.size();
        if (tickCount == 0) {
            return;
        }
        final Map<String, PositionMonitor> monitors = c.activeMonitors;
        if (monitors.isEmpty()) {
            return;
        }

        // HFT: For single-execution case (most common), avoid Set allocation entirely
        PositionMonitor singleMonitor = null;
        Set<PositionMonitor> monitorsToUpdate = null;

        for (int i = 0; i < tickCount; i++) {
            final long token = ticks.get(i).getInstrumentToken();
            final Set<String> executions = c.instrumentToExecutions.get(token);
            if (executions != null && !executions.isEmpty()) {
                for (String executionId : executions) {
                    final PositionMonitor monitor = monitors.get(executionId);
                    if (monitor != null && monitor.isActive()) {
                        // HFT: Optimize for single-monitor case (most common)
                        if (singleMonitor == null && monitorsToUpdate == null) {
                            singleMonitor = monitor;
                        } else if (singleMonitor != null && singleMonitor != monitor) {
                            // Transition to Set when we have multiple monitors
                            monitorsToUpdate = new HashSet<>(4);
                            monitorsToUpdate.add(singleMonitor);
                            monitorsToUpdate.add(monitor);
                            singleMonitor = null;
                        } else if (monitorsToUpdate != null) {
                            monitorsToUpdate.add(monitor);
                        }
                    }
                }
            }
        }

        // HFT: Update monitors - optimized for single-monitor case
        if (singleMonitor != null) {
            singleMonitor.updatePriceWithDifferenceCheck(ticks);
        } else if (monitorsToUpdate != null) {
            for (PositionMonitor monitor : monitorsToUpdate) {
                monitor.updatePriceWithDifferenceCheck(ticks);
            }
        }
    }

    private void resubscribeAll(UserWSContext c) {
        Set<Long> allTokens = c.instrumentToExecutions.keySet();
        if (!allTokens.isEmpty()) {
            log.info("[user={}] Resubscribing to {} instruments.", c.userId, allTokens.size());
            subscribe(c, new ArrayList<>(allTokens));
        }
    }

    @Override
    public void destroy() {
        log.info("Shutting down WebSocketService (per-user contexts).");
        for (UserWSContext c : contexts.values()) {
            try { disconnect(c); } catch (Exception ignore) {}
            if (c.reconnectScheduler != null) {
                c.reconnectScheduler.shutdownNow();
            }
        }
    }
}
