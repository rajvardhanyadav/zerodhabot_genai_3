package com.tradingbot.service.persistence;

import com.tradingbot.config.PersistenceConfig;
import com.tradingbot.service.UnifiedTradingService;
import com.tradingbot.service.session.UserSessionManager;
import com.tradingbot.util.CurrentUserContext;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Set;

/**
 * Service for end-of-day persistence operations.
 *
 * Automatically persists position snapshots and updates daily summaries
 * at the end of each trading day (after market close).
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "persistence.enabled", havingValue = "true", matchIfMissing = true)
public class EndOfDayPersistenceService {

    private final PersistenceConfig persistenceConfig;
    private final UnifiedTradingService unifiedTradingService;
    private final UserSessionManager userSessionManager;

    /**
     * Scheduled job to persist position snapshots at end of day.
     * Runs at 15:35 IST (5 minutes after market close) on weekdays.
     */
    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void persistEndOfDayPositions() {
        if (!persistenceConfig.isEnabled()) {
            log.debug("Persistence disabled, skipping end-of-day position snapshot");
            return;
        }

        log.info("Starting end-of-day position snapshot for all active users...");

        Set<String> activeUsers = userSessionManager.getActiveUserIds();
        if (activeUsers.isEmpty()) {
            log.info("No active user sessions found for end-of-day snapshot");
            return;
        }

        int successCount = 0;
        int failureCount = 0;

        for (String userId : activeUsers) {
            try {
                // Set user context for this thread
                CurrentUserContext.setUserId(userId);

                unifiedTradingService.persistPositionSnapshot();
                successCount++;

                log.debug("End-of-day position snapshot persisted for user: {}", userId);
            } catch (KiteException | IOException e) {
                failureCount++;
                log.error("Failed to persist end-of-day position snapshot for user={}: {}",
                        userId, e.getMessage());
            } catch (Exception e) {
                failureCount++;
                log.error("Failed to persist end-of-day position snapshot for user={}: {}",
                        userId, e.getMessage());
            } finally {
                CurrentUserContext.clear();
            }
        }

        log.info("End-of-day position snapshot completed: {} users successful, {} failed",
                successCount, failureCount);
    }

    /**
     * Scheduled job to persist position snapshots before pre-market (for overnight positions).
     * Runs at 9:00 IST on weekdays (before market opens).
     */
    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void persistPreMarketPositions() {
        if (!persistenceConfig.isEnabled()) {
            log.debug("Persistence disabled, skipping pre-market position snapshot");
            return;
        }

        log.info("Starting pre-market position snapshot for overnight positions...");

        Set<String> activeUsers = userSessionManager.getActiveUserIds();
        if (activeUsers.isEmpty()) {
            log.info("No active user sessions found for pre-market snapshot");
            return;
        }

        int successCount = 0;
        int failureCount = 0;

        for (String userId : activeUsers) {
            try {
                CurrentUserContext.setUserId(userId);
                unifiedTradingService.persistPositionSnapshot();
                successCount++;
            } catch (KiteException | IOException e) {
                failureCount++;
                log.error("Failed to persist pre-market position snapshot for user={}: {}",
                        userId, e.getMessage());
            } catch (Exception e) {
                failureCount++;
                log.error("Failed to persist pre-market position snapshot for user={}: {}",
                        userId, e.getMessage());
            } finally {
                CurrentUserContext.clear();
            }
        }

        log.info("Pre-market position snapshot completed: {} users successful, {} failed",
                successCount, failureCount);
    }
}

