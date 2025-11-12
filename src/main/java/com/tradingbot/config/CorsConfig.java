package com.tradingbot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * CORS Configuration
 * Configures Cross-Origin Resource Sharing for the application
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowCredentials(true);
        config.setAllowedOriginPatterns(List.of("*"));

        // Allow common headers
        config.setAllowedHeaders(List.of(
            "Origin", "Content-Type", "Accept", "Authorization",
            "Access-Control-Request-Method", "Access-Control-Request-Headers", "X-Requested-With"
        ));

        // Allow all standard HTTP methods
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));

        // Expose necessary headers
        config.setExposedHeaders(List.of(
            "Access-Control-Allow-Origin", "Access-Control-Allow-Credentials", "Authorization"
        ));

        config.setMaxAge(3600L); // Cache pre-flight request for 1 hour

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }
}
