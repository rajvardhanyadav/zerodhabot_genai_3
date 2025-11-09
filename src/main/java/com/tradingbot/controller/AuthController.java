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
    @Operation(summary = "Get Kite Connect login URL")
    public ResponseEntity<ApiResponse<String>> getLoginUrl() {
        try {
            String loginUrl = tradingService.getLoginUrl();
            return ResponseEntity.ok(ApiResponse.success("Login URL generated", loginUrl));
        } catch (Exception e) {
            log.error("Error generating login URL", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/session")
    @Operation(summary = "Generate session with request token")
    public ResponseEntity<ApiResponse<User>> generateSession(@RequestBody LoginRequest loginRequest) {
        try {
            User user = tradingService.generateSession(loginRequest.getRequestToken());
            log.info("Access Token: {}", user.accessToken);
            return ResponseEntity.ok(ApiResponse.success("Session generated successfully", user));
        } catch (KiteException | IOException e) {
            log.error("Error generating session", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/profile")
    @Operation(summary = "Get user profile")
    public ResponseEntity<ApiResponse<Profile>> getUserProfile() {
        try {
            Profile profile = tradingService.getUserProfile();
            return ResponseEntity.ok(ApiResponse.success(profile));
        } catch (KiteException | IOException e) {
            log.error("Error fetching user profile", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}

