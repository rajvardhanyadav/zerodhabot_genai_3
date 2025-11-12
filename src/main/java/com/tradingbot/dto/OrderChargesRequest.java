package com.tradingbot.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Builder;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderChargesRequest {

    @NotBlank(message = "Order ID is required")
    private String orderId;

    @NotBlank(message = "Exchange is required")
    private String exchange;

    @NotBlank(message = "Trading symbol is required")
    private String tradingsymbol;

    @NotBlank(message = "Transaction type is required")
    private String transactionType;

    @NotBlank(message = "Variety is required")
    private String variety;

    @NotBlank(message = "Product is required")
    private String product;

    @NotBlank(message = "Order type is required")
    private String orderType;

    @NotNull(message = "Quantity is required")
    @Positive(message = "Quantity must be positive")
    private Integer quantity;

    @NotNull(message = "Average price is required")
    @Positive(message = "Average price must be positive")
    private Double averagePrice;
}

