package com.dutch.gateway.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fixed-window per-identity (JWT sub) or per-IP request budget. Authentication
 * still runs independently; this only sheds abuse before the owning service.
 */
@Component
public final class GatewayRateLimitFilter extends OncePerRequestFilter {

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int capacity;
    private final Duration window;
    private final ObjectMapper objectMapper;

    public GatewayRateLimitFilter(
            @Value("${gateway.rate-limit.capacity:120}") int capacity,
            @Value("${gateway.rate-limit.window-seconds:60}") long windowSeconds,
            ObjectMapper objectMapper) {
        if (capacity < 1 || windowSeconds < 1) {
            throw new IllegalArgumentException("rate limit values must be positive");
        }
        this.capacity = capacity;
        this.window = Duration.ofSeconds(windowSeconds);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String key = identity(request);
        String correlationId = CorrelationId.resolve(request.getHeader(CorrelationId.HEADER));
        Window current = windows.compute(key, (ignored, existing) -> {
            Instant now = Instant.now();
            if (existing == null || existing.startedAt().plus(window).isBefore(now)) {
                return new Window(now, 1);
            }
            return new Window(existing.startedAt(), existing.count() + 1);
        });
        if (current.count() > capacity) {
            response.setStatus(429);
            response.setHeader(CorrelationId.HEADER, correlationId);
            response.setHeader("Retry-After", String.valueOf(window.toSeconds()));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", "rate_limited");
            error.put("message", "Too many requests. Retry later.");
            error.put("correlationId", correlationId);
            response.getWriter().write(objectMapper.writeValueAsString(Map.of("error", error)));
            return;
        }
        chain.doFilter(request, response);
    }

    /** The verified subject is not available yet; use the JWT sub only as a bucket key. */
    private String identity(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = authorization.substring(7).trim();
            String[] parts = token.split("\\.");
            if (parts.length == 3) {
                try {
                    String payload = new String(Base64.getUrlDecoder().decode(parts[1]),
                            StandardCharsets.UTF_8);
                    var node = objectMapper.readTree(payload);
                    if (node.hasNonNull("sub")) {
                        return "sub:" + node.get("sub").asText();
                    }
                } catch (RuntimeException | IOException ignored) {
                    // Fall through to the remote address.
                }
            }
        }
        return "ip:" + request.getRemoteAddr();
    }

    private record Window(Instant startedAt, int count) {
    }
}
