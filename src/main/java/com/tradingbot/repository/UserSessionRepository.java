package com.tradingbot.repository;

import com.tradingbot.entity.UserSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for user session persistence.
 *
 * CLOUD RUN COMPATIBILITY:
 * This repository enables session recovery across container instances.
 * Sessions are persisted to the database and can be restored when
 * a request arrives at an instance without the session in memory.
 *
 * HFT CONSIDERATIONS:
 * - Uses indexed queries for fast lookups
 * - Provides batch operations for cleanup
 * - Supports soft-delete pattern via active flag
 */
@Repository
public interface UserSessionRepository extends JpaRepository<UserSessionEntity, Long> {

    /**
     * Find an active session by user ID.
     * This is the primary lookup method used during session recovery.
     *
     * @param userId The user ID to find session for
     * @return Optional containing the session if found and active
     */
    Optional<UserSessionEntity> findByUserIdAndActiveTrue(String userId);

    /**
     * Find any session by user ID (active or inactive).
     * Used for checking existing sessions before creating new ones.
     *
     * @param userId The user ID to find session for
     * @return Optional containing the session if found
     */
    Optional<UserSessionEntity> findByUserId(String userId);

    /**
     * Find all active sessions.
     * Used for monitoring and administrative purposes.
     *
     * @return List of all active sessions
     */
    List<UserSessionEntity> findByActiveTrue();

    /**
     * Check if an active session exists for a user.
     *
     * @param userId The user ID to check
     * @return true if an active session exists
     */
    boolean existsByUserIdAndActiveTrue(String userId);

    /**
     * Deactivate session for a user (soft delete).
     * Used during logout to invalidate the session.
     *
     * @param userId The user ID whose session should be deactivated
     * @return Number of rows updated
     */
    @Modifying
    @Transactional
    @Query("UPDATE UserSessionEntity s SET s.active = false WHERE s.userId = :userId")
    int deactivateByUserId(@Param("userId") String userId);

    /**
     * Update the last accessed timestamp for a session.
     * Called periodically to track session activity.
     *
     * @param userId The user ID whose session should be touched
     * @param lastAccessedAt The new last accessed timestamp
     * @return Number of rows updated
     */
    @Modifying
    @Transactional
    @Query("UPDATE UserSessionEntity s SET s.lastAccessedAt = :lastAccessedAt WHERE s.userId = :userId AND s.active = true")
    int updateLastAccessedAt(@Param("userId") String userId, @Param("lastAccessedAt") Instant lastAccessedAt);

    /**
     * Delete expired and inactive sessions (cleanup).
     * Called by scheduled cleanup job to remove old sessions.
     *
     * @param expirationThreshold Sessions inactive before this timestamp will be deleted
     * @return Number of rows deleted
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM UserSessionEntity s WHERE s.active = false AND s.lastAccessedAt < :expirationThreshold")
    int deleteInactiveSessionsOlderThan(@Param("expirationThreshold") Instant expirationThreshold);

    /**
     * Find sessions that are potentially expired (for validation/cleanup).
     *
     * @param now Current timestamp
     * @return List of sessions past their expected expiration
     */
    @Query("SELECT s FROM UserSessionEntity s WHERE s.active = true AND s.expiresAt < :now")
    List<UserSessionEntity> findExpiredActiveSessions(@Param("now") Instant now);

    /**
     * Count active sessions (for monitoring).
     *
     * @return Number of active sessions
     */
    long countByActiveTrue();
}

