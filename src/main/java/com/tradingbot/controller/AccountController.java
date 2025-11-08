package com.tradingbot.controller;

import com.tradingbot.dto.ApiResponse;
import com.tradingbot.service.TradingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Margin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Account", description = "Account and margin endpoints")
public class AccountController {

    private final TradingService tradingService;

    @GetMapping("/margins/{segment}")
    @Operation(summary = "Get account margins for a segment (equity or commodity)")
    public ResponseEntity<ApiResponse<Margin>> getMargins(@PathVariable String segment) {
        try {
            Margin margins = tradingService.getMargins(segment);
            return ResponseEntity.ok(ApiResponse.success(margins));
        } catch (KiteException | IOException e) {
            log.error("Error fetching margins for segment: {}", segment, e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}

