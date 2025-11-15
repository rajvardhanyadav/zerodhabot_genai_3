package com.tradingbot.service.strategy.monitoring;

import com.tradingbot.config.KiteConfig;
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
 * This service manages the WebSocket connection for receiving ticks and routes them to active position monitors.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Getter
public class WebSocketService implements DisposableBean {

    private final KiteConnect kiteConnect;
    private final KiteConfig kiteConfig;
    private KiteTicker ticker;
    private final Map<String, PositionMonitor> activeMonitors = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> instrumentToExecutions = new ConcurrentHashMap<>();
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private final ReentrantLock connectionLock = new ReentrantLock();
    private ScheduledExecutorService reconnectScheduler;

    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long INITIAL_RECONNECT_DELAY_SECONDS = 5;

    // Controls whether to actually perform live subscription/connect. Useful for historical replay.
    private volatile boolean liveSubscriptionEnabled = true;

    @PostConstruct
    public void init() {
        this.reconnectScheduler = Executors.newSingleThreadScheduledExecutor();
    }

    /**
     * Unambiguous connection status accessor to avoid any naming clashes.
     */
    public boolean isWebSocketConnected() {
        return isConnected.get();
    }

    /**
     * Enable/disable live WebSocket subscription. When disabled, startMonitoring will register monitors
     * but will not attempt to connect/subscribe to live ticks. Synthetic ticks can still be fed.
     */
    public void setLiveSubscriptionEnabled(boolean enabled) {
        this.liveSubscriptionEnabled = enabled;
        log.info("Live WebSocket subscription {}", enabled ? "ENABLED" : "DISABLED");
    }

    /**
     * Checks if the WebSocket is currently connected.
     * @return true if connected, false otherwise.
     */
    public boolean isConnected() {
        return isConnected.get();
    }

    /**
     * Initialize and connect the WebSocket.
     * This method is idempotent and thread-safe.
     */
    public void connect() {
        if (!liveSubscriptionEnabled) {
            log.info("Live WebSocket subscription disabled; skipping connect().");
            return;
        }
        if (isConnected.get() || isConnecting.get()) {
            log.debug("WebSocket connection attempt ignored: already connected or connecting.");
            return;
        }

        if (connectionLock.tryLock()) {
            try {
                if (!isConnecting.compareAndSet(false, true)) {
                    return;
                }
                log.info("Attempting to establish WebSocket connection...");
                initializeAndConnectTicker();
            } finally {
                connectionLock.unlock();
            }
        } else {
            log.warn("WebSocket connection attempt is already in progress.");
        }
    }

    private void initializeAndConnectTicker() {
        try {
            String accessToken = validateAndGetAccessToken();
            setupTicker(accessToken);
            ticker.connect();
            log.info("WebSocket connection process initiated.");
        } catch (IllegalStateException e) {
            log.error("WebSocket connection failed pre-check: {}", e.getMessage());
            isConnecting.set(false);
            // Optionally schedule a reconnect attempt here if appropriate
        } catch (Exception e) {
            log.error("Fatal error during WebSocket initialization: {}", e.getMessage(), e);
            isConnecting.set(false);
            scheduleReconnection(1);
        }
    }

    private String validateAndGetAccessToken() throws IllegalStateException {
        String accessToken = kiteConnect.getAccessToken();
        if (accessToken == null || accessToken.isEmpty()) {
            throw new IllegalStateException("Access token not available. Please login first via /api/auth/session");
        }
        try {
            kiteConnect.getProfile();
            log.info("Access token validated successfully.");
            return accessToken;
        } catch (KiteException | IOException e) {
            log.error("Access token validation failed. Token may be expired. Please re-login.", e);
            throw new IllegalStateException("Invalid or expired access token. Please authenticate again.", e);
        }
    }

    private void setupTicker(String accessToken) {
        ticker = new KiteTicker(accessToken, kiteConfig.getApiKey());

        ticker.setOnConnectedListener(this::onConnected);
        ticker.setOnDisconnectedListener(this::onDisconnected);

        ticker.setOnErrorListener(new OnError() {
            @Override
            public void onError(Exception e) {
                WebSocketService.this.handleError(e);
            }

            @Override
            public void onError(KiteException e) {
                WebSocketService.this.handleError(e);
            }

            @Override
            public void onError(String message) {
                log.error("WebSocket error: {}", message);
            }
        });

        ticker.setOnTickerArrivalListener(this::processTicks);

        ticker.setTryReconnection(false); // We manage reconnection manually
    }

    private void onConnected() {
        isConnected.set(true);
        isConnecting.set(false);
        log.info("WebSocket connected successfully.");
        resubscribeAll();
    }

    private void onDisconnected() {
        isConnected.set(false);
        isConnecting.set(false);
        log.warn("WebSocket disconnected. Attempting to reconnect...");
        scheduleReconnection(1);
    }

    private void handleError(Throwable e) {
        log.error("WebSocket error occurred", e);
        isConnected.set(false);
        isConnecting.set(false);
        if (e instanceof KiteException) {
            handleKiteException((KiteException) e);
        } else {
            // For other exceptions, schedule a reconnect
            scheduleReconnection(1);
        }
    }

    private void handleKiteException(KiteException ke) {
        log.error("WebSocket Kite error: Code={}, Message='{}'", ke.code, ke.message);
        // 1001: Token Exception (invalid), 1009: User logged out
        if (ke.code == 1001 || ke.code == 1009) {
            log.error("Critical token error. Manual re-login required. Stopping reconnection attempts.");
        } else {
            scheduleReconnection(1);
        }
    }

    private void scheduleReconnection(int attempt) {
        if (!liveSubscriptionEnabled) {
            log.info("Live subscription disabled; not scheduling reconnection.");
            return;
        }
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            log.error("Exceeded max reconnection attempts. Please check the connection and credentials.");
            return;
        }
        if (isConnecting.get() || isConnected.get()) {
            return;
        }

        long delay = (long) (INITIAL_RECONNECT_DELAY_SECONDS * Math.pow(2, attempt - 1));
        log.info("Scheduling reconnection attempt {} in {} seconds.", attempt, delay);

        reconnectScheduler.schedule(() -> {
            log.info("Executing scheduled reconnection attempt #{}", attempt);
            connect();
            // If connect fails to acquire lock or start, it won't reschedule.
            // If it starts but fails, onDisconnected/onError will schedule the *next* attempt.
        }, delay, TimeUnit.SECONDS);
    }


    /**
     * Start monitoring a position.
     * @param executionId A unique identifier for the monitoring task.
     * @param monitor The PositionMonitor instance containing position details.
     */
    public void startMonitoring(String executionId, PositionMonitor monitor) {
        if (activeMonitors.putIfAbsent(executionId, monitor) != null) {
            log.warn("Monitoring for execution ID '{}' is already active.", executionId);
            return;
        }

        List<Long> tokensToSubscribe = new ArrayList<>();
        for (PositionMonitor.LegMonitor leg : monitor.getLegs()) {
            long token = leg.getInstrumentToken();
            instrumentToExecutions.computeIfAbsent(token, k -> new CopyOnWriteArraySet<>()).add(executionId);
            tokensToSubscribe.add(token);
        }

        if (!tokensToSubscribe.isEmpty()) {
            subscribe(tokensToSubscribe);
        }
        log.info("Started monitoring execution: {} with {} legs.", executionId, monitor.getLegs().size());
    }

    /**
     * Stop monitoring a position.
     * @param executionId The identifier of the monitoring task to stop.
     */
    public void stopMonitoring(String executionId) {
        PositionMonitor monitor = activeMonitors.remove(executionId);
        if (monitor == null) {
            log.warn("No active monitor found for execution ID '{}' to stop.", executionId);
            return;
        }

        monitor.stop();
        List<Long> tokensToUnsubscribe = new ArrayList<>();
        for (PositionMonitor.LegMonitor leg : monitor.getLegs()) {
            long token = leg.getInstrumentToken();
            Set<String> executions = instrumentToExecutions.get(token);
            if (executions != null) {
                executions.remove(executionId);
                if (executions.isEmpty()) {
                    instrumentToExecutions.remove(token);
                    tokensToUnsubscribe.add(token);
                }
            }
        }

        if (!tokensToUnsubscribe.isEmpty()) {
            unsubscribe(tokensToUnsubscribe);
        }
        log.info("Stopped monitoring execution: {}", executionId);
    }

    private void subscribe(List<Long> tokens) {
        if (!liveSubscriptionEnabled) {
            log.info("Live subscription disabled; skipping subscribe() for {} tokens.", tokens.size());
            return;
        }
        if (!isConnected.get()) {
            log.warn("Cannot subscribe - WebSocket not connected. Will attempt to connect.");
            connect(); // connect() is non-blocking and safe to call
            // Subscription will happen automatically on connection via resubscribeAll
            return;
        }
        try {
            ArrayList<Long> tokenList = new ArrayList<>(tokens);
            ticker.subscribe(tokenList);
            ticker.setMode(tokenList, KiteTicker.modeLTP);
            log.info("Subscribed to {} new instruments.", tokens.size());
        } catch (Exception e) {
            log.error("Error subscribing to instruments: {}", e.getMessage(), e);
        }
    }

    private void unsubscribe(List<Long> tokens) {
        if (!liveSubscriptionEnabled) {
            log.info("Live subscription disabled; skipping unsubscribe() for {} tokens.", tokens.size());
            return;
        }
        if (!isConnected.get() || ticker == null) {
            log.warn("Cannot unsubscribe - WebSocket not connected.");
            return;
        }
        try {
            ticker.unsubscribe(new ArrayList<>(tokens));
            log.info("Unsubscribed from {} instruments.", tokens.size());
        } catch (Exception e) {
            log.error("Error unsubscribing from instruments: {}", e.getMessage(), e);
        }
    }

    private void processTicks(ArrayList<Tick> ticks) {
        for (Tick tick : ticks) {
            long token = tick.getInstrumentToken();
            Set<String> executions = instrumentToExecutions.get(token);
            if (executions != null) {
                for (String executionId : executions) {
                    PositionMonitor monitor = activeMonitors.get(executionId);
                    if (monitor != null && monitor.isActive()) {
                        // Pass only the relevant tick to the monitor
                        monitor.updatePriceWithDifferenceCheck(new ArrayList<>(Collections.singletonList(tick)));
                    }
                }
            }
        }
    }

    private void resubscribeAll() {
        if (!liveSubscriptionEnabled) {
            log.info("Live subscription disabled; skipping resubscribeAll().");
            return;
        }
        Set<Long> allTokens = instrumentToExecutions.keySet();
        if (!allTokens.isEmpty()) {
            log.info("Resubscribing to {} active instruments after reconnection.", allTokens.size());
            subscribe(new ArrayList<>(allTokens));
        }
    }

    /**
     * Disconnects the WebSocket client.
     */
    public void disconnect() {
        connectionLock.lock();
        try {
            if (ticker != null && isConnected.get()) {
                log.info("Disconnecting WebSocket client.");
                ticker.disconnect();
                isConnected.set(false);
                isConnecting.set(false);
            }
        } finally {
            connectionLock.unlock();
        }
    }

    @Override
    public void destroy() {
        log.info("Shutting down WebSocketService.");
        disconnect();
        if (reconnectScheduler != null) {
            reconnectScheduler.shutdownNow();
        }
    }

    /**
     * Get the number of active position monitors.
     * @return The count of active monitors.
     */
    public int getActiveMonitorsCount() {
        return activeMonitors.size();
    }

    // --- Additions for historical replay ---

    /**
     * Get the monitor for a specific execution id, if any.
     */
    public Optional<PositionMonitor> getMonitor(String executionId) {
        return Optional.ofNullable(activeMonitors.get(executionId));
    }

    /**
     * Feed synthetic ticks directly to a specific execution's monitor.
     */
    public void feedTicks(String executionId, ArrayList<Tick> ticks) {
        PositionMonitor monitor = activeMonitors.get(executionId);
        if (monitor != null && monitor.isActive()) {
            monitor.updatePriceWithDifferenceCheck(ticks);
        }
    }
}
