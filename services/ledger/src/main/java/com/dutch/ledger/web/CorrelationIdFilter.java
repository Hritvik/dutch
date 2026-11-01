package com.dutch.ledger.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-ID";
    public static final String ATTRIBUTE = CorrelationIdFilter.class.getName();
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = resolve(request.getHeader(HEADER));
        request.setAttribute(ATTRIBUTE, correlationId);
        response.setHeader(HEADER, correlationId);
        chain.doFilter(request, response);
    }

    public static String resolve(String supplied) {
        return supplied != null && SAFE.matcher(supplied).matches() ? supplied : generate();
    }

    private static String generate() {
        return "cor_" + UUID.randomUUID().toString().replace("-", "");
    }
}
