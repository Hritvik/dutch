package com.dutch.gateway.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fixed-window per-identity (JWT sub) or per-IP request budget. Authentication
 * still runs independently; this only sheds abuse before the owning service.
 */
public final class GatewayRateLimitFilter extends OncePerRequestFilter {

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int capacity;
    private final Duration window;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;

    public GatewayRateLimitFilter(
            @Value("${gateway.rate-limit.capacity:120}") int capacity,
            @Value("${gateway.rate-limit.window-seconds:60}") long windowSeconds,
            ObjectMapper objectMapper,
            MeterRegistry meters) {
        if (capacity < 1 || windowSeconds < 1) {
            throw new IllegalArgumentException("rate limit values must be positive");
        }
        this.capacity = capacity;
        this.window = Duration.ofSeconds(windowSeconds);
        this.objectMapper = objectMapper;
        this.meters = meters;
    }

    /** Test-only convenience without a registry. */
    GatewayRateLimitFilter(int capacity, long windowSeconds, ObjectMapper objectMapper) {
        this(capacity, windowSeconds, objectMapper, new SimpleMeterRegistry());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (windows.size() > 4096) {
            Instant now = Instant.now();
            windows.entrySet().removeIf(entry -> entry.getValue().startedAt().plus(window).isBefore(now));
        }
        meters.counter("gateway.requests").increment();
        String key = "ip:" + request.getRemoteAddr();
        String correlationId = CorrelationId.resolve(request.getHeader(CorrelationId.HEADER));
        Window current = windows.compute(key, (ignored, existing) -> {
            Instant now = Instant.now();
            if (existing == null || existing.startedAt().plus(window).isBefore(now)) {
                return new Window(now, 1);
            }
            return new Window(existing.startedAt(), existing.count() + 1);
        });
        if (current.count() > capacity) {
            meters.counter("gateway.rate_limited").increment();
            response.setStatus(429);
            response.setHeader(CorrelationId.HEADER, correlationId);
            response.setHeader("Retry-After", String.valueOf(window.toSeconds()));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", "rate_limited");
            error.put("message", "Request rate limit exceeded");
            error.put("correlationId", correlationId);
            response.getWriter().write(objectMapper.writeValueAsString(Map.of("error", error)));
            return;
        }
        chain.doFilter(request, response);
    }

    private record Window(Instant startedAt, int count) {
    }
}
