package com.tradingbot.service.strategy.monitoring;

import com.tradingbot.config.KiteConfig;
import com.tradingbot.service.session.UserSessionManager;
import com.tradingbot.util.CurrentUserContext;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Tick;
import com.zerodhatech.ticker.KiteTicker;
import com.zerodhatech.ticker.OnError;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
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
@RequiredArgsConstructor
@Slf4j
@Getter
public class WebSocketService implements DisposableBean {

    private final UserSessionManager sessionManager;
    private final KiteConfig kiteConfig;

    // Global override for live subscription. Historical replay toggles this.
    private volatile boolean globalLiveSubscriptionEnabled = true;

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
        volatile boolean liveSubscriptionEnabled = true; // per-user toggle

        UserWSContext(String userId) {
            this.userId = userId;
        }
    }

    private final Map<String, UserWSContext> contexts = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("WebSocketService initialized. Global live subscription: {}", globalLiveSubscriptionEnabled);
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

    /** Global toggle retained for Historical Replay compatibility */
    public void setLiveSubscriptionEnabled(boolean enabled) {
        this.globalLiveSubscriptionEnabled = enabled;
        log.info("Global live WebSocket subscription {}", enabled ? "ENABLED" : "DISABLED");
    }

    /** Optional per-user live subscription toggle */
    public void setLiveSubscriptionEnabledForCurrentUser(boolean enabled) {
        UserWSContext c = ctx();
        c.liveSubscriptionEnabled = enabled;
        log.info("[user={}] Live subscription {}", c.userId, enabled ? "ENABLED" : "DISABLED");
    }

    /** Connect WebSocket for current user (idempotent) */
    public void connect() {
        UserWSContext c = ctx();
        tryConnect(c);
    }

    private void tryConnect(UserWSContext c) {
        if (!globalLiveSubscriptionEnabled || !c.liveSubscriptionEnabled) {
            log.info("[user={}] Live subscription disabled; skipping connect().", c.userId);
            return;
        }
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
    }

    private void onDisconnected(UserWSContext c) {
        c.isConnected.set(false);
        c.isConnecting.set(false);
        log.warn("[user={}] WebSocket disconnected. Scheduling reconnect...", c.userId);
        scheduleReconnection(c, 1);
    }

    private void handleError(UserWSContext c, Throwable e) {
        log.error("[user={}] WebSocket error", c.userId, e);
        c.isConnected.set(false);
        c.isConnecting.set(false);
        if (e instanceof KiteException ke) {
            if (ke.code == 1001 || ke.code == 1009) {
                log.error("[user={}] Critical token error. Manual re-login required.", c.userId);
                return;
            }
        }
        scheduleReconnection(c, 1);
    }

    private void scheduleReconnection(UserWSContext c, int attempt) {
        if (!globalLiveSubscriptionEnabled || !c.liveSubscriptionEnabled) {
            log.info("[user={}] Live subscription disabled; not scheduling reconnection.", c.userId);
            return;
        }
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
        if (!globalLiveSubscriptionEnabled || !c.liveSubscriptionEnabled) {
            log.info("[user={}] Live subscription disabled; skipping subscribe() for {} tokens.", c.userId, tokens.size());
            return;
        }
        if (!c.isConnected.get()) {
            log.warn("[user={}] Not connected. Attempting to connect before subscribing.", c.userId);
            tryConnect(c);
            return;
        }
        try {
            ArrayList<Long> tokenList = new ArrayList<>(tokens);
            c.ticker.subscribe(tokenList);
            c.ticker.setMode(tokenList, KiteTicker.modeLTP);
            log.info("[user={}] Subscribed to {} instruments.", c.userId, tokens.size());
        } catch (Exception e) {
            log.error("[user={}] Error subscribing: {}", c.userId, e.getMessage(), e);
        }
    }

    private void unsubscribe(UserWSContext c, List<Long> tokens) {
        if (!globalLiveSubscriptionEnabled || !c.liveSubscriptionEnabled) {
            log.info("[user={}] Live subscription disabled; skipping unsubscribe() for {} tokens.", c.userId, tokens.size());
            return;
        }
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

    private void processTicks(UserWSContext c, ArrayList<Tick> ticks) {
        log.info("New ticks received: count={}", ticks.size());
        for (Tick tick : ticks) {
            long token = tick.getInstrumentToken();
            Set<String> executions = c.instrumentToExecutions.get(token);
            if (executions != null) {
                for (String executionId : executions) {
                    PositionMonitor monitor = c.activeMonitors.get(executionId);
                    if (monitor != null && monitor.isActive()) {
                        monitor.updatePriceWithDifferenceCheck(ticks);
                    }
                    break;
                }
                break;
            }
        }
    }

    private void resubscribeAll(UserWSContext c) {
        if (!globalLiveSubscriptionEnabled || !c.liveSubscriptionEnabled) {
            log.info("[user={}] Live subscription disabled; skipping resubscribeAll().", c.userId);
            return;
        }
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
