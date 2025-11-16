package com.tradingbot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * CORS Configuration.
 * Configures Cross-Origin Resource Sharing for the application.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        // Allow requests from any origin. Since we want to support arbitrary online frontends,
        // we disable credentials so that the wildcard origin is allowed by browsers.
        config.setAllowCredentials(false);
        // For Spring Boot 2.x/3.x, either setAllowedOrigins(List.of("*")) or addAllowedOriginPattern("*")
        // is acceptable for wildcard. Using addAllowedOriginPattern for broader compatibility.
        config.addAllowedOriginPattern("*");

        // Allow all headers and methods, including preflight OPTIONS.
        config.setAllowedHeaders(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));

        // Expose common headers if needed by frontend (optional).
        config.setExposedHeaders(List.of("Authorization", "X-User-Id"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
