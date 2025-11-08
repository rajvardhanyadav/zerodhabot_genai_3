package com.tradingbot.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI tradingBotOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Zerodha Trading Bot API")
                        .description("RESTful API for trading bot with Kite Connect integration")
                        .version("1.0")
                        .contact(new Contact()
                                .name("Trading Bot Team")
                                .email("support@tradingbot.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0.html")));
    }
}