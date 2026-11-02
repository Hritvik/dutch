package com.dutch.gateway.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Thin request router. It forwards the authenticated public surface to the
 * owning service without taking on domain behaviour or a shared database.
 */
@Component
public final class GatewayRoutingFilter extends OncePerRequestFilter {

    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length", "expect");
    private static final Set<String> DROPPED = Set.of(
            "forwarded", "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto",
            "x-forwarded-port", "x-original-url", "x-rewrite-url");

    private final GatewayRouteProperties routes;
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    @Autowired
    public GatewayRoutingFilter(GatewayRouteProperties routes, ObjectMapper objectMapper) {
        this.routes = routes;
        this.objectMapper = objectMapper;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals("/api/v1/me")
                || path.startsWith("/api/v1/me/")
                || path.startsWith("/api/v1/ledger")
                || path.startsWith("/api/v1/groups")
                || path.startsWith("/api/v1/notifications")
                || path.startsWith("/api/v1/reports"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException {
        String correlationId = CorrelationId.resolve(request.getHeader(CorrelationId.HEADER));
        response.setHeader(CorrelationId.HEADER, correlationId);

        if (hasTraversal(request.getRequestURI())) {
            writeError(response, 400, "invalid_request", "Request is invalid", correlationId);
            return;
        }

        HttpRequest upstream;
        try {
            upstream = requestBuilder(request, target(request), correlationId).build();
        } catch (IllegalArgumentException exception) {
            writeError(response, 400, "invalid_request", "Request is invalid", correlationId);
            return;
        }

        try {
            HttpResponse<InputStream> answer = client.send(upstream, HttpResponse.BodyHandlers.ofInputStream());
            response.setStatus(answer.statusCode());
            answer.headers().map().forEach((name, values) -> {
                if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                    values.forEach(value -> response.addHeader(name, value));
                }
            });
            response.setHeader(CorrelationId.HEADER, correlationId);
            try (InputStream body = answer.body()) {
                body.transferTo(response.getOutputStream());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            writeError(response, 503, "service_unavailable", "Service is temporarily unavailable", correlationId);
        } catch (IOException exception) {
            writeError(response, 503, "service_unavailable", "Service is temporarily unavailable", correlationId);
        }
    }

    private URI target(HttpServletRequest request) {
        String path = request.getRequestURI();
        String base = path.equals("/api/v1/me") || path.startsWith("/api/v1/me/")
                ? routes.umsBaseUrl()
                : path.startsWith("/api/v1/notifications")
                        ? routes.notificationBaseUrl()
                        : routes.ledgerBaseUrl();
        String query = request.getQueryString();
        return URI.create(base + path + (query == null ? "" : "?" + query));
    }

    private HttpRequest.Builder requestBuilder(HttpServletRequest request, URI target, String correlationId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(15))
                .method(request.getMethod(), bodyPublisher(request));
        Set<String> connectionTokens = connectionTokens(request.getHeader("Connection"));
        request.getHeaderNames().asIterator().forEachRemaining(name -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!HOP_BY_HOP.contains(lower)
                    && !DROPPED.contains(lower)
                    && !connectionTokens.contains(lower)
                    && !CorrelationId.HEADER.equalsIgnoreCase(name)) {
                request.getHeaders(name).asIterator().forEachRemaining(value -> builder.header(name, value));
            }
        });
        builder.header(CorrelationId.HEADER, correlationId);
        return builder;
    }

    private HttpRequest.BodyPublisher bodyPublisher(HttpServletRequest request) {
        if (!BODY_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))) {
            return HttpRequest.BodyPublishers.noBody();
        }
        return HttpRequest.BodyPublishers.ofInputStream(() -> {
            try {
                return request.getInputStream();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        });
    }

    private static boolean hasTraversal(String rawPath) {
        try {
            return URLDecoder.decode(rawPath, StandardCharsets.UTF_8).contains("..");
        } catch (IllegalArgumentException exception) {
            return true;
        }
    }

    private static Set<String> connectionTokens(String header) {
        if (header == null || header.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(header.split(","))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private void writeError(HttpServletResponse response, int status, String code, String message,
                            String correlationId) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(CorrelationId.HEADER, correlationId);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("correlationId", correlationId);
        objectMapper.writeValue(response.getOutputStream(), Map.of("error", error));
    }
}
