package com.dutch.ums.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class UmsSecurityConfiguration {
    private final ObjectMapper mapper;

    public UmsSecurityConfiguration(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Bean
    SecurityFilterChain umsSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((request, response, failure) ->
                                envelope(request, response, 401, "unauthorized", "Authentication is required"))
                        .accessDeniedHandler((request, response, failure) ->
                                envelope(request, response, 403, "forbidden", "Access is denied")))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, failure) ->
                                envelope(request, response, 401, "unauthorized", "Authentication is required"))
                        .accessDeniedHandler((request, response, failure) ->
                                envelope(request, response, 403, "forbidden", "Access is denied")))
                .build();
    }

    private void envelope(HttpServletRequest request, HttpServletResponse response,
                          int status, String code, String message) throws IOException {
        Object correlation = request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), Map.of("error", Map.of(
                "code", code,
                "message", message,
                "correlationId", correlation == null ? "" : correlation)));
    }
}
