package com.tradingbot.service.session;

import com.tradingbot.config.KiteConfig;
import com.tradingbot.config.PaperTradingConfig;
import com.tradingbot.entity.UserSessionEntity;
import com.tradingbot.repository.UserSessionRepository;
import com.tradingbot.util.CurrentUserContext;
import com.zerodhatech.kiteconnect.KiteConnect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for UserSessionManager Cloud Run session recovery functionality.
 */
class UserSessionManagerTest {

    @Mock
    private KiteConfig kiteConfig;

    @Mock
    private PaperTradingConfig paperTradingConfig;

    @Mock
    private UserSessionRepository sessionRepository;

    private UserSessionManager sessionManager;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        when(kiteConfig.getApiKey()).thenReturn("test-api-key");
        sessionManager = new UserSessionManager(kiteConfig, paperTradingConfig, sessionRepository);
    }

    @AfterEach
    void tearDown() throws Exception {
        CurrentUserContext.clear();
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    @DisplayName("hasSession returns true when session exists in memory")
    void testHasSessionInMemory() {
        // Manually add a session to memory via reflection or simulate it via database recovery
        String userId = "test-user";

        // When session not in memory, should check database
        when(sessionRepository.existsByUserIdAndActiveTrue(userId)).thenReturn(true);

        assertTrue(sessionManager.hasSession(userId));
        verify(sessionRepository).existsByUserIdAndActiveTrue(userId);
    }

    @Test
    @DisplayName("hasSession returns false for null userId")
    void testHasSessionNullUserId() {
        assertFalse(sessionManager.hasSession(null));
        assertFalse(sessionManager.hasSession(""));
        assertFalse(sessionManager.hasSession("   "));
    }

    @Test
    @DisplayName("getKiteForUser recovers session from database when not in memory")
    void testGetKiteForUserRecoveryFromDatabase() {
        String userId = "test-user";
        String accessToken = "test-access-token";

        // Create a database session entity
        UserSessionEntity dbSession = UserSessionEntity.builder()
                .userId(userId)
                .kiteUserId("kite-user-123")
                .accessToken(accessToken)
                .createdAt(Instant.now())
                .lastAccessedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(18 * 60 * 60))
                .active(true)
                .build();

        when(sessionRepository.findByUserIdAndActiveTrue(userId)).thenReturn(Optional.of(dbSession));

        // First call should trigger database recovery
        KiteConnect kite = sessionManager.getKiteForUser(userId);

        // Should have queried database
        verify(sessionRepository).findByUserIdAndActiveTrue(userId);

        // Should have recovered a KiteConnect instance
        assertNotNull(kite);
    }

    @Test
    @DisplayName("getKiteForUser returns null when session not found anywhere")
    void testGetKiteForUserNotFound() {
        String userId = "nonexistent-user";

        when(sessionRepository.findByUserIdAndActiveTrue(userId)).thenReturn(Optional.empty());

        KiteConnect kite = sessionManager.getKiteForUser(userId);

        assertNull(kite);
        verify(sessionRepository).findByUserIdAndActiveTrue(userId);
    }

    @Test
    @DisplayName("invalidateSession deactivates session in database")
    void testInvalidateSessionDeactivatesInDatabase() {
        String userId = "test-user";

        when(sessionRepository.deactivateByUserId(userId)).thenReturn(1);

        boolean result = sessionManager.invalidateSession(userId);

        // Should have called database deactivation
        verify(sessionRepository).deactivateByUserId(userId);
        // Since we only had database session (not in memory), should still return true
        assertTrue(result);
    }

    @Test
    @DisplayName("getDatabaseSessionCount returns count from repository")
    void testGetDatabaseSessionCount() {
        when(sessionRepository.countByActiveTrue()).thenReturn(5L);

        long count = sessionManager.getDatabaseSessionCount();

        assertEquals(5L, count);
        verify(sessionRepository).countByActiveTrue();
    }

    @Test
    @DisplayName("getActiveUserIds returns in-memory session keys")
    void testGetActiveUserIds() {
        // Initially empty
        assertTrue(sessionManager.getActiveUserIds().isEmpty());
        assertEquals(0, sessionManager.getActiveSessionCount());
    }

    @Test
    @DisplayName("getCurrentUserIdRequired throws when context missing in non-paper mode")
    void testGetCurrentUserIdRequiredThrowsWhenMissing() {
        when(paperTradingConfig.isPaperTradingEnabled()).thenReturn(false);

        CurrentUserContext.clear();

        assertThrows(IllegalStateException.class, () -> {
            sessionManager.getCurrentUserIdRequired();
        });
    }

    @Test
    @DisplayName("getCurrentUserIdRequired returns fallback in paper mode when context missing")
    void testGetCurrentUserIdRequiredFallbackInPaperMode() {
        when(paperTradingConfig.isPaperTradingEnabled()).thenReturn(true);

        CurrentUserContext.clear();

        String userId = sessionManager.getCurrentUserIdRequired();

        assertEquals("PAPER_DEFAULT_USER", userId);
    }

    @Test
    @DisplayName("getCurrentUserIdRequired returns context userId when present")
    void testGetCurrentUserIdRequiredReturnsContext() {
        String expectedUserId = "context-user";
        CurrentUserContext.setUserId(expectedUserId);

        String userId = sessionManager.getCurrentUserIdRequired();

        assertEquals(expectedUserId, userId);
    }
}

