package com.dutch.ledger.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

@Configuration
public class LedgerSecurityConfiguration {

    @Bean
    SecurityFilterChain ledgerSecurityFilterChain(
            HttpSecurity http,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers("/api/v1/groups/**").authenticated()
                        .requestMatchers("/api/v1/direct-expenses/**").authenticated()
                        .requestMatchers("/api/v1/series/**").authenticated()
                        .requestMatchers("/api/v1/ledger/**").authenticated()
                        .requestMatchers("/api/v1/reports/**").authenticated()
                        .requestMatchers("/api/v1/privacy/**").authenticated()
                        // The UMS report ingress authenticates with a service token in the
                        // controller, not a Dutch user credential.
                        .requestMatchers("/internal/ums/**", "/internal/members/**").permitAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .build();
    }

    @Bean
    AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, exception) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            write(response, objectMapper, request, HttpStatus.UNAUTHORIZED,
                    "unauthorized", "Authentication is required");
        };
    }

    @Bean
    AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, exception) -> write(
                response, objectMapper, request, HttpStatus.FORBIDDEN,
                "forbidden", "Access is denied");
    }

    static void write(HttpServletResponse response, ObjectMapper objectMapper, HttpServletRequest request,
                      HttpStatus status, String code, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        String correlationId = (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        if (correlationId == null) {
            correlationId = CorrelationIdFilter.resolve(request.getHeader(CorrelationIdFilter.HEADER));
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(CorrelationIdFilter.HEADER, correlationId);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("correlationId", correlationId);
        objectMapper.writeValue(response.getOutputStream(), Map.of("error", error));
    }
}
