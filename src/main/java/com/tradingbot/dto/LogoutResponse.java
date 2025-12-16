package com.tradingbot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for logout operation.
 *
 * <p>Contains detailed statistics about the cleanup performed during logout:
 * <ul>
 *   <li>User identification</li>
 *   <li>Number of strategies stopped</li>
 *   <li>Number of scheduled restarts cancelled</li>
 *   <li>WebSocket disconnection status</li>
 *   <li>Paper trading reset status</li>
 *   <li>Session invalidation status</li>
 *   <li>Total duration of logout operation</li>
 * </ul>
 *
 * @author Trading Bot Team
 * @since 4.2
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LogoutResponse {

    /**
     * The user ID that was logged out.
     * May be null if no active session was found.
     */
    private String userId;

    /**
     * Number of active strategies that were stopped during logout.
     */
    private int strategiesStopped;

    /**
     * Number of scheduled strategy auto-restarts that were cancelled.
     */
    private int scheduledRestartsCancelled;

    /**
     * Whether the WebSocket connection was disconnected.
     */
    private boolean webSocketDisconnected;

    /**
     * Whether paper trading state was reset (orders, positions, accounts).
     */
    private boolean paperTradingReset;

    /**
     * Whether the Kite session was invalidated.
     */
    private boolean sessionInvalidated;

    /**
     * Total duration of the logout operation in milliseconds.
     */
    private long durationMs;
}

