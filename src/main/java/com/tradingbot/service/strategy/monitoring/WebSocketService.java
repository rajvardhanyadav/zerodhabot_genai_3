package com.tradingbot.service.strategy.monitoring;

import com.tradingbot.config.KiteConfig;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.ticker.KiteTicker;
import com.zerodhatech.models.Tick;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket service for real-time price updates
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebSocketService {

    private final KiteConnect kiteConnect;
    private final KiteConfig kiteConfig;
    private KiteTicker ticker;
    private final Map<String, PositionMonitor> activeMonitors = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> instrumentToExecutions = new ConcurrentHashMap<>();
    private volatile boolean isConnected = false;

    /**
     * Initialize WebSocket connection
     */
    public synchronized void connect() {
        if (ticker != null && isConnected) {
            log.debug("WebSocket already connected");
            return;
        }

        try {
            String accessToken = kiteConnect.getAccessToken();
            if (accessToken == null || accessToken.isEmpty()) {
                log.error("Access token not available for WebSocket connection. Please login first via /api/auth/session");
                throw new IllegalStateException("Access token not set. Please authenticate first.");
            }

            // Validate token is not just the config value (which might be expired)
            log.info("Attempting WebSocket connection...");
            log.info("API Key : {}", kiteConfig.getApiKey());
            log.debug("Access Token length: {}", accessToken);

            // Verify we can make API calls before attempting WebSocket connection
            try {
                kiteConnect.getProfile();
                log.info("Access token validated successfully");
            } catch (KiteException | IOException e) {
                log.error("Access token validation failed. Token may be expired. Please re-login via /api/auth/session");
                throw new IllegalStateException("Invalid or expired access token. Please authenticate again.", e);
            }

            ticker = new KiteTicker(accessToken, kiteConfig.getApiKey());

            ticker.setOnConnectedListener(() -> {
                isConnected = true;
                log.info("WebSocket connected successfully");
                resubscribeAll();
            });

            ticker.setOnDisconnectedListener(() -> {
                isConnected = false;
                log.warn("WebSocket disconnected");
            });

            ticker.setOnErrorListener(new com.zerodhatech.ticker.OnError() {
                @Override
                public void onError(Exception exception) {
                    log.error("WebSocket error occurred", exception);
                    isConnected = false;
                }

                @Override
                public void onError(KiteException kiteException) {
                    log.error("WebSocket Kite error occurred", kiteException);
                    isConnected = false;
                }

                @Override
                public void onError(String error) {
                    log.error("WebSocket error occurred: {}", error);
                    isConnected = false;
                }
            });

            ticker.setOnTickerArrivalListener(this::processTicks);

            ticker.setTryReconnection(true);

            try {
                ticker.setMaximumRetries(10);
                ticker.setMaximumRetryInterval(30);
            } catch (KiteException e) {
                log.warn("Could not set retry configuration: {}", e.getMessage());
            }

            ticker.connect();
            log.info("WebSocket connection initiated");

        } catch (IllegalStateException e) {
            log.error("WebSocket connection failed: {}", e.getMessage());
            isConnected = false;
            throw e;
        } catch (Exception e) {
            log.error("Error connecting WebSocket: {}", e.getMessage(), e);
            isConnected = false;
            throw new RuntimeException("Failed to establish WebSocket connection: " + e.getMessage(), e);
        }
    }

    /**
     * Start monitoring a position
     */
    public void startMonitoring(String executionId, PositionMonitor monitor) {
        activeMonitors.put(executionId, monitor);

        // Subscribe to instrument tokens
        List<Long> tokens = new ArrayList<>();
        for (PositionMonitor.LegMonitor leg : monitor.getLegs()) {
            long token = leg.getInstrumentToken();
            tokens.add(token);

            instrumentToExecutions
                .computeIfAbsent(token, k -> ConcurrentHashMap.newKeySet())
                .add(executionId);
        }

        if (!tokens.isEmpty()) {
            subscribe(tokens);
        }

        log.info("Started monitoring execution: {} with {} legs", executionId, monitor.getLegs().size());
    }

    /**
     * Stop monitoring a position
     */
    public void stopMonitoring(String executionId) {
        PositionMonitor monitor = activeMonitors.remove(executionId);

        if (monitor != null) {
            monitor.stop();

            // Unsubscribe from tokens if no other execution is using them
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
    }

    /**
     * Subscribe to instrument tokens
     */
    private void subscribe(List<Long> tokens) {
        if (!isConnected || ticker == null) {
            log.warn("Cannot subscribe - WebSocket not connected");
            connect();
            return;
        }

        try {
            ArrayList<Long> tokenList = new ArrayList<>(tokens);
            ticker.subscribe(tokenList);
            ticker.setMode(tokenList, KiteTicker.modeLTP);
            log.info("Subscribed to {} instruments", tokens.size());
        } catch (Exception e) {
            log.error("Error subscribing to instruments: {}", e.getMessage(), e);
        }
    }

    /**
     * Unsubscribe from instrument tokens
     */
    private void unsubscribe(List<Long> tokens) {
        if (!isConnected || ticker == null) {
            return;
        }

        try {
            ArrayList<Long> tokenList = new ArrayList<>(tokens);
            ticker.unsubscribe(tokenList);
            log.info("Unsubscribed from {} instruments", tokens.size());
        } catch (Exception e) {
            log.error("Error unsubscribing from instruments: {}", e.getMessage(), e);
        }
    }

    /**
     * Process incoming ticks
     */
    private void processTicks(ArrayList<Tick> ticks) {
        for (Tick tick : ticks) {
            long token = tick.getInstrumentToken();
            double ltp = tick.getLastTradedPrice();

            Set<String> executions = instrumentToExecutions.get(token);
            if (executions != null) {
                for (String executionId : executions) {
                    PositionMonitor monitor = activeMonitors.get(executionId);
                    if (monitor != null && monitor.isActive()) {
                        //monitor.updatePrice(token, ltp);
//                        monitor.updatePriceWithPnLDiffCheck(token,ltp);
                        monitor.updatePriceWithDifferenceCheck(ticks);
                    }
                }
            }
        }
    }

    /**
     * Resubscribe to all active instruments after reconnection
     */
    private void resubscribeAll() {
        Set<Long> allTokens = new HashSet<>(instrumentToExecutions.keySet());
        if (!allTokens.isEmpty()) {
            subscribe(new ArrayList<>(allTokens));
        }
    }

    /**
     * Disconnect WebSocket
     */
    public synchronized void disconnect() {
        if (ticker != null) {
            try {
                ticker.disconnect();
                isConnected = false;
                log.info("WebSocket disconnected");
            } catch (Exception e) {
                log.error("Error disconnecting WebSocket: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Check if WebSocket is connected
     */
    public boolean isConnected() {
        return isConnected;
    }

    /**
     * Get active monitors count
     */
    public int getActiveMonitorsCount() {
        return activeMonitors.size();
    }
}
