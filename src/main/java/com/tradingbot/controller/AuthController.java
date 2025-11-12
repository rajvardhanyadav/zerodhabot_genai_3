package com.tradingbot.controller;

import com.tradingbot.dto.ApiResponse;
import com.tradingbot.dto.LoginRequest;
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

import java.io.IOException;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Authentication", description = "Kite Connect authentication endpoints")
public class AuthController {

    private final TradingService tradingService;

    @GetMapping("/login-url")
    @Operation(summary = "Get Kite Connect login URL",
               description = "Generate login URL for Kite Connect authentication flow")
    public ResponseEntity<ApiResponse<String>> getLoginUrl() {
        String loginUrl = tradingService.getLoginUrl();
        return ResponseEntity.ok(ApiResponse.success("Login URL generated", loginUrl));
    }

    @PostMapping("/session")
    @Operation(summary = "Generate session with request token",
               description = "Exchange request token for access token and create user session")
    public ResponseEntity<ApiResponse<User>> generateSession(@RequestBody LoginRequest loginRequest)
            throws KiteException, IOException {
        User user = tradingService.generateSession(loginRequest.getRequestToken());
        log.info("Session generated successfully for user: {}", user.userId);
        return ResponseEntity.ok(ApiResponse.success("Session generated successfully", user));
    }

    @GetMapping("/profile")
    @Operation(summary = "Get user profile",
               description = "Fetch authenticated user's profile information")
    public ResponseEntity<ApiResponse<Profile>> getUserProfile() throws KiteException, IOException {
        Profile profile = tradingService.getUserProfile();
        return ResponseEntity.ok(ApiResponse.success(profile));
    }
}
