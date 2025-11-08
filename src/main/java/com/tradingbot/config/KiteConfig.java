package com.tradingbot.config;

import com.zerodhatech.kiteconnect.KiteConnect;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "kite")
@Getter
@Setter
public class KiteConfig {

    private String apiKey;
    private String apiSecret;
    private String accessToken;
    private String loginUrl;

    @Bean
    public KiteConnect kiteConnect() {
        KiteConnect kiteConnect = new KiteConnect(apiKey);
        kiteConnect.setUserId("user_id");

        // Set access token if available
        if (accessToken != null && !accessToken.isEmpty()) {
            kiteConnect.setAccessToken(accessToken);
        }

        return kiteConnect;
    }
}

