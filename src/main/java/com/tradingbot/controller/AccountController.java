package com.tradingbot.controller;

import com.tradingbot.dto.ApiResponse;
import com.tradingbot.service.TradingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Margin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
    @Operation(summary = "Get account margins for a segment",
               description = "Fetch available margins, used margins, and collateral for the specified trading segment (equity or commodity)")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Margins fetched successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid segment or missing X-User-Id header"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Kite API error or internal server error")
    })
    public ResponseEntity<ApiResponse<Margin>> getMargins(
            @Parameter(description = "Trading segment: equity or commodity", required = true, example = "equity")
            @PathVariable String segment) throws KiteException, IOException {
        Margin margins = tradingService.getMargins(segment);
        return ResponseEntity.ok(ApiResponse.success(margins));
    }
}
