package com.tradingbot.controller;

import com.tradingbot.dto.ApiResponse;
import com.tradingbot.dto.LoginRequest;
import com.tradingbot.dto.LogoutResponse;
import com.tradingbot.service.LogoutService;
import com.tradingbot.service.TradingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Profile;
import com.zerodhatech.models.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.io.IOException;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Authentication", description = "Kite Connect authentication endpoints")
public class AuthController {

    private final TradingService tradingService;
    private final LogoutService logoutService;

    @GetMapping("/login-url")
    @Operation(summary = "Get Kite Connect login URL",
               description = "Generate login URL for Kite Connect authentication flow")
    public ResponseEntity<ApiResponse<String>> getLoginUrl() {
        log.debug("API Request - Get Kite Connect login URL");
        String loginUrl = tradingService.getLoginUrl();
        log.debug("API Response - Login URL generated successfully");
        return ResponseEntity.ok(ApiResponse.success("Login URL generated", loginUrl));
    }

    @PostMapping("/session")
    @Operation(summary = "Generate session with request token",
               description = "Exchange request token for access token and create user session")
    public ResponseEntity<ApiResponse<User>> generateSession(@Valid @RequestBody LoginRequest loginRequest)
            throws KiteException, IOException {
        log.info("API Request - Generate session with request token");
        User user = tradingService.generateSession(loginRequest.getRequestToken());
        log.info("API Response - Session generated successfully for user: {}", user.userId);
        return ResponseEntity.ok(ApiResponse.success("Session generated successfully", user));
    }

    @GetMapping("/profile")
    @Operation(summary = "Get user profile",
               description = "Fetch authenticated user's profile information")
    public ResponseEntity<ApiResponse<Profile>> getUserProfile() throws KiteException, IOException {
        log.debug("API Request - Get user profile");
        Profile profile = tradingService.getUserProfile();
        log.debug("API Response - Profile fetched for user: {}", profile.userName);
        return ResponseEntity.ok(ApiResponse.success(profile));
    }

    /**
     * Logout the current user and clean up all associated resources.
     *
     * <p>This endpoint performs a comprehensive cleanup including:
     * <ul>
     *   <li>Stopping all active trading strategies</li>
     *   <li>Cancelling scheduled strategy auto-restarts</li>
     *   <li>Disconnecting WebSocket connections</li>
     *   <li>Resetting paper trading state</li>
     *   <li>Invalidating the Kite session</li>
     * </ul>
     *
     * <p><b>Idempotency:</b> Multiple logout calls are safe and will not cause errors.
     *
     * @return Logout result with cleanup statistics
     */
    @PostMapping("/logout")
    @Operation(summary = "Logout user",
               description = "Logout the current user and clean up all session data, strategies, and WebSocket connections")
    public ResponseEntity<ApiResponse<LogoutResponse>> logout() {
        log.info("API Request - User logout initiated");

        LogoutService.LogoutResult result = logoutService.logout();

        LogoutResponse response = LogoutResponse.builder()
                .userId(result.userId())
                .strategiesStopped(result.strategiesStopped())
                .scheduledRestartsCancelled(result.scheduledRestartsCancelled())
                .webSocketDisconnected(result.webSocketDisconnected())
                .paperTradingReset(result.paperTradingReset())
                .sessionInvalidated(result.sessionInvalidated())
                .durationMs(result.durationMs())
                .build();

        if (result.success()) {
            log.info("API Response - Logout successful for user: {}", result.userId());
            return ResponseEntity.ok(ApiResponse.success(result.message(), response));
        } else {
            log.warn("API Response - Logout failed: {}", result.message());
            return ResponseEntity.badRequest().body(ApiResponse.error(result.message()));
        }
    }
}
