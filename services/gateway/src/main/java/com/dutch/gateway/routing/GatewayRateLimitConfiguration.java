package com.dutch.gateway.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.security.SecurityProperties;

@Configuration
public class GatewayRateLimitConfiguration {

    @Bean
    FilterRegistrationBean<GatewayRateLimitFilter> rateLimitFilter(
            ObjectMapper objectMapper,
            @Value("${gateway.rate-limit.capacity:120}") int capacity,
            @Value("${gateway.rate-limit.window-seconds:60}") long windowSeconds) {
        FilterRegistrationBean<GatewayRateLimitFilter> registration = new FilterRegistrationBean<>(
                new GatewayRateLimitFilter(capacity, windowSeconds, objectMapper));
        // Shed before the security chain so unauthenticated floods do not reach JWT parsing.
        registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER - 1);
        registration.addUrlPatterns("/api/v1/*");
        return registration;
    }
}
