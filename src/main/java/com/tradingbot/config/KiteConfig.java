package com.tradingbot.config;

import com.zerodhatech.kiteconnect.KiteConnect;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Kite Connect Configuration
 * Configures Zerodha Kite API credentials and connection
 */
@Configuration
@ConfigurationProperties(prefix = "kite")
@Getter
@Setter
public class KiteConfig {

    private String apiKey;
    private String apiSecret;
    private String accessToken;

    @Bean
    public KiteConnect kiteConnect() {
        KiteConnect kiteConnect = new KiteConnect(apiKey);

        // Set access token if available
        if (accessToken != null && !accessToken.isEmpty()) {
            kiteConnect.setAccessToken(accessToken);
        }

        return kiteConnect;
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
