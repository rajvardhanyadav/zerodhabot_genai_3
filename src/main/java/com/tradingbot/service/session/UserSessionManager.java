package com.tradingbot.service.session;

import com.tradingbot.config.KiteConfig;
import com.tradingbot.config.PaperTradingConfig;
import com.tradingbot.util.CurrentUserContext;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserSessionManager {

    private final KiteConfig kiteConfig;
    private final PaperTradingConfig paperTradingConfig;

    private final Map<String, KiteConnect> sessions = new ConcurrentHashMap<>();

    public String getCurrentUserIdRequired() {
        String id = CurrentUserContext.getUserId();
        if (id == null || id.isBlank()) {
            if (paperTradingConfig.isPaperTradingEnabled()) {
                String fallback = "PAPER_DEFAULT_USER";
                log.warn("UserContext missing in UserSessionManager; falling back to {} in paper mode", fallback);
                return fallback;
            }
            throw new IllegalStateException("User context is missing. Provide X-User-Id header.");
        }
        return id;
    }

    public boolean hasSession(String userId) {
        return sessions.containsKey(userId);
    }

    public KiteConnect getKiteForUser(String userId) {
        return sessions.get(userId);
    }

    public KiteConnect getRequiredKiteForCurrentUser() {
        String userId = getCurrentUserIdRequired();
        KiteConnect kc = sessions.get(userId);
        if (kc == null) {
            throw new IllegalStateException("No active session for user. Please authenticate via /api/auth/session.");
        }
        return kc;
    }

    /**
     * Backward compatible method when user header is already set.
     */
    public User createOrReplaceSessionForCurrentUser(String requestToken) throws KiteException, IOException {
        String headerUserId = CurrentUserContext.getUserId();
        return createSessionInternal(requestToken, headerUserId);
    }

    /**
     * Creates a session. If X-User-Id header was provided, uses it; otherwise resolves userId from Kite response and stores session under that id.
     * Returns the Kite User object.
     */
    public User createSessionFromRequestToken(String requestToken) throws KiteException, IOException {
        String headerUserId = CurrentUserContext.getUserId();
        return createSessionInternal(requestToken, headerUserId);
    }

    private User createSessionInternal(String requestToken, String headerUserId) throws KiteException, IOException {
        KiteConnect kc = new KiteConnect(kiteConfig.getApiKey());
        User user = kc.generateSession(requestToken, kiteConfig.getApiSecret());
        kc.setAccessToken(user.accessToken);
        // Determine final userId key
        String effectiveUserId = (headerUserId != null && !headerUserId.isBlank()) ? headerUserId.trim() : user.userId;
        sessions.put(effectiveUserId, kc);
        // If header was absent, set context for this request so downstream logging sees it
        if (headerUserId == null || headerUserId.isBlank()) {
            CurrentUserContext.setUserId(effectiveUserId);
            log.debug("UserContext set from Kite userId={} during session creation", effectiveUserId);
        }
        log.info("Kite session stored for userId={}" , effectiveUserId);
        return user;
    }

    public void invalidateSessionForCurrentUser() {
        String userId = getCurrentUserIdRequired();
        sessions.remove(userId);
        log.info("Kite session invalidated for userId={}", userId);
    }
}
