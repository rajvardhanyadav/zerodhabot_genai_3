package com.tradingbot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response after placing, modifying, or cancelling an order")
public class OrderResponse {
    @Schema(description = "Unique order identifier", example = "220303000845481")
    private String orderId;

    @Schema(description = "Current order status", example = "COMPLETE")
    private String status;

    @Schema(description = "Human-readable status message", example = "Order placed successfully")
    private String message;
}
