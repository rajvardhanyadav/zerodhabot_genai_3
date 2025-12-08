package com.tradingbot.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Swagger/OpenAPI Configuration
 * Configures API documentation using OpenAPI 3.0
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI tradingBotOpenAPI() {
        return new OpenAPI()
                .info(buildApiInfo())
                .servers(buildServerList())
                .tags(buildApiTags());
    }

    private Info buildApiInfo() {
        return new Info()
                .title("Zerodha Trading Bot API")
                .description("""
                        RESTful API for automated trading bot with Kite Connect integration.
                        
                        Key Features:
                        • OAuth Authentication with Zerodha Kite
                        • Order Management (Place, Modify, Cancel)
                        • Portfolio & Position Tracking
                        • Real-time Market Data
                        • Automated Trading Strategies (ATM Straddle, Sell ATM Straddle)
                        • Real-time Position Monitoring with WebSocket
                        • Stop Loss & Target Management
                        • Paper Trading Mode for risk-free testing
                        • GTT (Good Till Triggered) Orders
                        """)
                .version("2.0.0")
                .contact(new Contact()
                        .name("Trading Bot Support")
                        .email("support@tradingbot.com")
                        .url("https://github.com/tradingbot"))
                .license(new License()
                        .name("Apache 2.0")
                        .url("https://www.apache.org/licenses/LICENSE-2.0.html"));
    }

    private List<Server> buildServerList() {
        return List.of(
                new Server()
                        .url("http://localhost:8080")
                        .description("Local Development Server"),
                new Server()
                        .url("https://your-app.onrender.com")
                        .description("Production Server")
        );
    }

    private List<Tag> buildApiTags() {
        return List.of(
                new Tag().name("Authentication")
                        .description("Kite Connect OAuth authentication"),
                new Tag().name("Orders")
                        .description("Order management operations"),
                new Tag().name("Portfolio")
                        .description("Portfolio, positions, and holdings"),
                new Tag().name("Market Data")
                        .description("Real-time and historical market data"),
                new Tag().name("Account")
                        .description("Account information and margins"),
                new Tag().name("GTT Orders")
                        .description("Good Till Triggered orders"),
                new Tag().name("Trading Strategies")
                        .description("Automated trading strategies with real-time monitoring"),
                new Tag().name("Position Monitoring")
                        .description("Real-time position monitoring with WebSocket"),
                new Tag().name("Paper Trading")
                        .description("Paper trading account management"),
                new Tag().name("Health")
                        .description("Application health checks")
        );
    }
}