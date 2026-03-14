package com.tradingbot.controller;

import com.tradingbot.dto.ApiResponse;
import com.tradingbot.dto.OrderRequest;
import com.tradingbot.dto.OrderResponse;
import com.tradingbot.dto.OrderChargesResponse;
import com.tradingbot.service.UnifiedTradingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Order;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Orders", description = "Order management endpoints - Automatically routes to Paper or Live trading")
public class OrderController {

    private final UnifiedTradingService unifiedTradingService;

    @PostMapping
    @Operation(summary = "Place a new order",
               description = "Place order in Paper Trading or Live Trading mode based on configuration")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Order placed successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid order parameters or missing X-User-Id header"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Kite API error or order placement failed")
    })
    public ResponseEntity<ApiResponse<OrderResponse>> placeOrder(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Order parameters including symbol, quantity, type, and price", required = true)
            @Valid @RequestBody OrderRequest orderRequest)
            throws KiteException, IOException {
        log.info("API Request - Place order: {} {} {} @ {}",
            orderRequest.getTransactionType(), orderRequest.getQuantity(),
            orderRequest.getTradingSymbol(), orderRequest.getOrderType());
        OrderResponse response = unifiedTradingService.placeOrder(orderRequest);
        log.info("API Response - Order placed: {} with status: {}", response.getOrderId(), response.getStatus());
        return ResponseEntity.ok(ApiResponse.success("Order placed successfully", response));
    }

    @PutMapping("/{orderId}")
    @Operation(summary = "Modify an existing order",
               description = "Modify order parameters like price, quantity, or order type")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Order modified successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid parameters"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Order not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Kite API error")
    })
    public ResponseEntity<ApiResponse<OrderResponse>> modifyOrder(
            @Parameter(description = "Order ID to modify", required = true, example = "220303000845481")
            @PathVariable String orderId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Updated order parameters", required = true)
            @Valid @RequestBody OrderRequest orderRequest) throws KiteException, IOException {
        log.info("API Request - Modify order: {}", orderId);
        OrderResponse response = unifiedTradingService.modifyOrder(orderId, orderRequest);
        log.info("API Response - Order modified: {} with status: {}", orderId, response.getStatus());
        return ResponseEntity.ok(ApiResponse.success("Order modified successfully", response));
    }

    @DeleteMapping("/{orderId}")
    @Operation(summary = "Cancel an order",
               description = "Cancel a pending order by order ID")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Order cancelled successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Order not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Kite API error")
    })
    public ResponseEntity<ApiResponse<OrderResponse>> cancelOrder(
            @Parameter(description = "Order ID to cancel", required = true, example = "220303000845481")
            @PathVariable String orderId)
            throws KiteException, IOException {
        log.info("API Request - Cancel order: {}", orderId);
        OrderResponse response = unifiedTradingService.cancelOrder(orderId);
        log.info("API Response - Order cancelled: {} with status: {}", orderId, response.getStatus());
        return ResponseEntity.ok(ApiResponse.success("Order cancelled successfully", response));
    }

    @GetMapping
    @Operation(summary = "Get all orders for the day",
               description = "Fetch all orders placed today across all instruments")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Orders fetched successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Missing X-User-Id header"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Kite API error")
    })
    public ResponseEntity<ApiResponse<List<Order>>> getOrders() throws KiteException, IOException {
        log.debug("API Request - Get all orders");
        List<Order> orders = unifiedTradingService.getOrders();
        log.debug("API Response - Returning {} orders", orders.size());
        return ResponseEntity.ok(ApiResponse.success(orders));
    }

    @GetMapping("/{orderId}/history")
    @Operation(summary = "Get order history",
               description = "Fetch complete history of order state changes")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Order history fetched successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Order not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Kite API error")
    })
    public ResponseEntity<ApiResponse<List<Order>>> getOrderHistory(
            @Parameter(description = "Order ID to retrieve history for", required = true, example = "220303000845481")
            @PathVariable String orderId)
            throws KiteException, IOException {
        log.debug("API Request - Get order history for: {}", orderId);
        List<Order> orderHistory = unifiedTradingService.getOrderHistory(orderId);
        log.debug("API Response - Returning {} order history records for: {}", orderHistory.size(), orderId);
        return ResponseEntity.ok(ApiResponse.success(orderHistory));
    }

    @GetMapping("/charges")
    @Operation(summary = "Get charges for all executed orders today",
               description = "Fetches detailed charge breakdown from Kite API for all completed orders placed today. " +
                           "Includes brokerage, STT, exchange charges, GST, SEBI charges, and stamp duty.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Order charges fetched successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Missing X-User-Id header"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Kite API error")
    })
    public ResponseEntity<ApiResponse<List<OrderChargesResponse>>> getOrderCharges() throws KiteException, IOException {
        log.info("API Request - Get order charges");
        List<OrderChargesResponse> charges = unifiedTradingService.getOrderCharges();
        log.info("API Response - Returning charges for {} orders", charges.size());
        return ResponseEntity.ok(ApiResponse.success("Order charges fetched successfully", charges));
    }
}
