package com.tradingbot.config;

import com.tradingbot.util.CurrentUserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Component
@Order(1)
public class UserContextFilter extends OncePerRequestFilter {

    private static final String USER_HEADER = "X-User-Id";
    private static final Set<String> PROTECTED_PREFIXES = Set.of(
            "/api/orders",
            "/api/portfolio",
            "/api/market",
            "/api/account",
            "/api/gtt",
            "/api/strategies",
            "/api/monitoring",
            "/api/historical" // removed /api/auth/session so initial session creation can occur without header
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // Skip user enforcement for CORS preflight requests
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        String userId = request.getHeader(USER_HEADER);
        boolean requiresUser = PROTECTED_PREFIXES.stream().anyMatch(path::startsWith);

        try {
            if (requiresUser && (userId == null || userId.isBlank())) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                String body = "{\"success\":false,\"message\":\"Missing X-User-Id header\"}";
                response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (userId != null && !userId.isBlank()) {
                CurrentUserContext.setUserId(userId.trim());
            }
            filterChain.doFilter(request, response);
        } finally {
            CurrentUserContext.clear();
        }
    }
}
