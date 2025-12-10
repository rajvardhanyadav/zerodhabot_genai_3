package com.tradingbot.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Entity for tracking WebSocket connection events.
 * Useful for debugging connectivity issues.
 */
@Entity
@Table(name = "websocket_events", indexes = {
    @Index(name = "idx_ws_timestamp", columnList = "timestamp"),
    @Index(name = "idx_ws_event_type", columnList = "eventType"),
    @Index(name = "idx_ws_user", columnList = "userId")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebSocketEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64)
    private String userId;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column(nullable = false, length = 30)
    private String eventType; // CONNECTED, DISCONNECTED, RECONNECTED, ERROR, SUBSCRIPTION_ADDED, SUBSCRIPTION_REMOVED

    @Column(length = 500)
    private String details;

    private Integer subscribedTokenCount;

    private Integer reconnectAttempt;

    @Column(length = 500)
    private String errorMessage;

    @Column(length = 100)
    private String errorCode;

    private Long latencyMs;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
    }
}

