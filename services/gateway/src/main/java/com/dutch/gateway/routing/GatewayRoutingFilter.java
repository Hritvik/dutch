package com.dutch.gateway.routing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Thin request router. It forwards the authenticated public surface to the
 * owning service without taking on domain behaviour or a shared database.
 */
@Component
public final class GatewayRoutingFilter extends OncePerRequestFilter {

    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length", "expect");

    private final GatewayRouteProperties routes;
    private final HttpClient client;

    @Autowired
    public GatewayRoutingFilter(GatewayRouteProperties routes) {
        this(routes, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    }

    GatewayRoutingFilter(GatewayRouteProperties routes, HttpClient client) {
        this.routes = routes;
        this.client = client;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals("/api/v1/me")
                || path.startsWith("/api/v1/me/")
                || path.startsWith("/api/v1/ledger"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = correlationId(request.getHeader(CORRELATION_HEADER));
        response.setHeader(CORRELATION_HEADER, correlationId);

        HttpRequest upstream;
        try {
            upstream = requestBuilder(request, target(request), correlationId).build();
        } catch (IllegalArgumentException exception) {
            writeError(response, 400, "invalid_request_target", correlationId);
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
            response.setHeader(CORRELATION_HEADER, correlationId);
            try (InputStream body = answer.body()) {
                body.transferTo(response.getOutputStream());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            writeError(response, 502, "upstream_unavailable", correlationId);
        } catch (IOException exception) {
            writeError(response, 502, "upstream_unavailable", correlationId);
        }
    }

    private URI target(HttpServletRequest request) {
        String path = request.getRequestURI();
        String base = path.equals("/api/v1/me") || path.startsWith("/api/v1/me/")
                ? routes.umsBaseUrl()
                : routes.ledgerBaseUrl();
        String query = request.getQueryString();
        return URI.create(base + path + (query == null ? "" : "?" + query));
    }

    private HttpRequest.Builder requestBuilder(HttpServletRequest request, URI target, String correlationId)
            throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(15))
                .method(request.getMethod(), bodyPublisher(request));
        request.getHeaderNames().asIterator().forEachRemaining(name -> {
            if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))
                    && !CORRELATION_HEADER.equalsIgnoreCase(name)) {
                request.getHeaders(name).asIterator().forEachRemaining(value -> builder.header(name, value));
            }
        });
        builder.header(CORRELATION_HEADER, correlationId);
        return builder;
    }

    private HttpRequest.BodyPublisher bodyPublisher(HttpServletRequest request) {
        if (request.getContentLengthLong() <= 0) {
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

    private static String correlationId(String supplied) {
        if (supplied != null && !supplied.isBlank() && supplied.length() <= 128
                && supplied.chars().noneMatch(Character::isISOControl)) {
            return supplied;
        }
        return UUID.randomUUID().toString();
    }

    private static void writeError(HttpServletResponse response, int status, String code, String correlationId)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(CORRELATION_HEADER, correlationId);
        response.getWriter().write("{\"error\":{\"code\":\"" + code
                + "\",\"message\":\"The requested service is unavailable.\",\"correlationId\":\""
                + correlationId + "\"}}");
    }
}
