package com.tradingbot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to generate a Kite Connect session using a request token")
public class LoginRequest {
    @Schema(description = "Request token obtained from Kite Connect login redirect", example = "abcdef123456", requiredMode = Schema.RequiredMode.REQUIRED)
    private String requestToken;
}
