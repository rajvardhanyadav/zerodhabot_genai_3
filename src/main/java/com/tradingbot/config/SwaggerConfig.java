package com.tradingbot.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI tradingBotOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Zerodha Trading Bot API")
                        .description("""
                                RESTful API for automated trading bot with Kite Connect integration.
                                
                                Features:
                                - OAuth Authentication with Zerodha Kite
                                - Order Management (Place, Modify, Cancel)
                                - Portfolio & Position Tracking
                                - Real-time Market Data
                                - Automated Trading Strategies (ATM Straddle, ATM Strangle)
                                - Real-time Position Monitoring with WebSocket
                                - Stop Loss & Target Profit Management
                                - GTT (Good Till Triggered) Orders
                                
                                Version 2.0.0 - Enhanced with real-time monitoring features
                                """)
                        .version("2.0.0")
                        .contact(new Contact()
                                .name("Trading Bot Support")
                                .email("support@tradingbot.com")
                                .url("https://github.com/tradingbot"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0.html")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8080")
                                .description("Local Development Server"),
                        new Server()
                                .url("https://your-app.onrender.com")
                                .description("Production Server")
                ))
                .tags(List.of(
                        new Tag()
                                .name("Authentication")
                                .description("Kite Connect OAuth authentication endpoints"),
                        new Tag()
                                .name("Orders")
                                .description("Order management - Place, modify, cancel orders"),
                        new Tag()
                                .name("Portfolio")
                                .description("Portfolio management - Positions, holdings, trades"),
                        new Tag()
                                .name("Market Data")
                                .description("Real-time and historical market data"),
                        new Tag()
                                .name("Account")
                                .description("Account information and margins"),
                        new Tag()
                                .name("GTT")
                                .description("Good Till Triggered orders management"),
                        new Tag()
                                .name("Trading Strategies")
                                .description("Automated trading strategies with real-time monitoring (ATM Straddle, ATM Strangle)"),
                        new Tag()
                                .name("Position Monitoring")
                                .description("Real-time position monitoring with WebSocket, Stop Loss, and Target management"),
                        new Tag()
                                .name("Health")
                                .description("Application health check endpoints")
                ));
    }
}