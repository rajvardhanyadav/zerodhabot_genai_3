package com.tradingbot;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@OpenAPIDefinition(
    info = @Info(
        title = "Zerodha Trading Bot API",
        version = "4.2",
        description = "High-Frequency Trading REST API with Kite Connect integration. " +
            "Supports live and paper trading modes, automated strategy execution (ATM Straddle, Sell ATM Straddle, Short Strangle), " +
            "real-time WebSocket position monitoring, GTT orders, backtesting, and comprehensive trading history. " +
            "All protected endpoints require the X-User-Id header for multi-user context.",
        contact = @Contact(
            name = "Trading Bot Support",
            email = "support@tradingbot.com",
            url = "https://github.com/tradingbot"
        ),
        license = @License(
            name = "Apache 2.0",
            url = "https://www.apache.org/licenses/LICENSE-2.0.html"
        )
    )
)
public class TradingBotApplication {
    public static void main(String[] args) {
        SpringApplication.run(TradingBotApplication.class, args);
    }
}