package com.tradingbot.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Persistent storage for user Kite sessions.
 *
 * CLOUD RUN COMPATIBILITY:
 * This entity enables session persistence across multiple container instances.
 * When a request arrives at an instance without the session in memory,
 * the session can be restored from the database using the stored access token.
 *
 * SECURITY NOTE:
 * Access tokens are stored encrypted in production. The token is used to
 * re-establish KiteConnect sessions without requiring re-authentication.
 *
 * HFT CONSIDERATIONS:
 * - Uses optimistic locking to handle concurrent updates
 * - Indexed by userId for fast lookups
 * - Soft expiration tracked to avoid using stale tokens
 */
@Entity
@Table(name = "user_sessions", indexes = {
    @Index(name = "idx_user_session_user_id", columnList = "userId", unique = true),
    @Index(name = "idx_user_session_expires_at", columnList = "expiresAt")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The application user ID (from X-User-Id header or Kite userId).
     * This is the primary lookup key.
     */
    @Column(nullable = false, unique = true, length = 64)
    private String userId;

    /**
     * The original Kite user ID returned from authentication.
     * May differ from userId if custom user IDs are used.
     */
    @Column(nullable = false, length = 64)
    private String kiteUserId;

    /**
     * The Kite access token for API authentication.
     * Used to restore KiteConnect sessions on new instances.
     */
    @Column(nullable = false, length = 256)
    private String accessToken;

    /**
     * The Kite public token (optional, used for WebSocket).
     */
    @Column(length = 256)
    private String publicToken;

    /**
     * Timestamp when the session was created.
     */
    @Column(nullable = false)
    private Instant createdAt;

    /**
     * Timestamp when the session was last accessed/validated.
     */
    @Column(nullable = false)
    private Instant lastAccessedAt;

    /**
     * Estimated expiration time of the Kite session.
     * Kite sessions typically expire next morning (~6 AM IST).
     * This is a soft expiration - actual validity should be verified with Kite API.
     */
    @Column(nullable = false)
    private Instant expiresAt;

    /**
     * Whether the session is currently active.
     * Set to false on logout or when token is invalidated.
     */
    @Column(nullable = false)
    private boolean active;

    /**
     * Version for optimistic locking to handle concurrent updates.
     */
    @Version
    private Long version;

    /**
     * Check if the session is potentially expired based on stored expiration time.
     * Note: This is a soft check - actual token validity should be verified with Kite API.
     */
    public boolean isPotentiallyExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /**
     * Check if the session is stale (created > 16 hours ago).
     * Kite sessions typically expire at end of day/next morning.
     */
    public boolean isStale() {
        return Instant.now().isAfter(createdAt.plusSeconds(16 * 60 * 60));
    }

    /**
     * Update the last accessed timestamp.
     */
    public void touch() {
        this.lastAccessedAt = Instant.now();
    }

    /**
     * Deactivate the session (used during logout).
     */
    public void deactivate() {
        this.active = false;
    }
}

