package com.tradingbot.controller;

import com.tradingbot.dto.ApiResponse;
import com.tradingbot.service.TradingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.GTT;
import com.zerodhatech.models.GTTParams;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/gtt")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "GTT Orders", description = "Good Till Triggered (GTT) order management endpoints")
public class GTTController {

    private final TradingService tradingService;

    @GetMapping
    @Operation(summary = "Get all GTT orders",
               description = "Fetch all active GTT orders")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "GTT orders fetched successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Missing X-User-Id header"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Kite API error")
    })
    public ResponseEntity<ApiResponse<List<GTT>>> getGTTs() throws KiteException, IOException {
        List<GTT> gtts = tradingService.getGTTs();
        return ResponseEntity.ok(ApiResponse.success(gtts));
    }

    @PostMapping
    @Operation(summary = "Place a GTT order",
               description = "Create a new Good Till Triggered order with specified conditions")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "GTT order placed successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid GTT parameters or missing header"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Kite API error")
    })
    public ResponseEntity<ApiResponse<GTT>> placeGTT(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "GTT order parameters including trigger conditions", required = true)
            @RequestBody GTTParams gttParams) throws KiteException, IOException {
        GTT gtt = tradingService.placeGTT(gttParams);
        return ResponseEntity.ok(ApiResponse.success("GTT order placed successfully", gtt));
    }

    @GetMapping("/{triggerId}")
    @Operation(summary = "Get GTT order by ID",
               description = "Fetch specific GTT order details by trigger ID")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "GTT order fetched successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "GTT order not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Kite API error")
    })
    public ResponseEntity<ApiResponse<GTT>> getGTT(
            @Parameter(description = "GTT trigger ID", required = true, example = "123456")
            @PathVariable int triggerId) throws KiteException, IOException {
        GTT gtt = tradingService.getGTT(triggerId);
        return ResponseEntity.ok(ApiResponse.success(gtt));
    }

    @PutMapping("/{triggerId}")
    @Operation(summary = "Modify a GTT order",
               description = "Update trigger conditions or order parameters of an existing GTT order")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "GTT order modified successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid parameters"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "GTT order not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Kite API error")
    })
    public ResponseEntity<ApiResponse<GTT>> modifyGTT(
            @Parameter(description = "GTT trigger ID to modify", required = true, example = "123456")
            @PathVariable int triggerId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Updated GTT parameters", required = true)
            @RequestBody GTTParams gttParams) throws KiteException, IOException {
        GTT gtt = tradingService.modifyGTT(triggerId, gttParams);
        return ResponseEntity.ok(ApiResponse.success("GTT order modified successfully", gtt));
    }

    @DeleteMapping("/{triggerId}")
    @Operation(summary = "Cancel a GTT order",
               description = "Delete/cancel an active GTT order by trigger ID")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "GTT order cancelled successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "GTT order not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Kite API error")
    })
    public ResponseEntity<ApiResponse<GTT>> cancelGTT(
            @Parameter(description = "GTT trigger ID to cancel", required = true, example = "123456")
            @PathVariable int triggerId) throws KiteException, IOException {
        GTT result = tradingService.cancelGTT(triggerId);
        return ResponseEntity.ok(ApiResponse.success("GTT order cancelled successfully", result));
    }
}
