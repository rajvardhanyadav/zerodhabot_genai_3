package com.tradingbot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Logout operation result with cleanup statistics")
public class LogoutResponse {

    @Schema(description = "The user ID that was logged out (null if no active session)", example = "AB1234")
    private String userId;

    @Schema(description = "Number of active strategies that were stopped during logout", example = "2")
    private int strategiesStopped;

    @Schema(description = "Number of scheduled strategy auto-restarts that were cancelled", example = "1")
    private int scheduledRestartsCancelled;

    @Schema(description = "Whether the WebSocket connection was disconnected", example = "true")
    private boolean webSocketDisconnected;

    @Schema(description = "Whether paper trading state was reset (orders, positions, accounts)", example = "true")
    private boolean paperTradingReset;

    @Schema(description = "Whether the Kite session was invalidated", example = "true")
    private boolean sessionInvalidated;

    @Schema(description = "Total duration of the logout operation in milliseconds", example = "245")
    private long durationMs;
}

