package com.dutch.gateway.routing;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway.routes")
public record GatewayRouteProperties(String umsBaseUrl, String ledgerBaseUrl) {

    public GatewayRouteProperties {
        umsBaseUrl = normalize(umsBaseUrl, "http://localhost:8082", "gateway.routes.ums-base-url");
        ledgerBaseUrl = normalize(ledgerBaseUrl, "http://localhost:8083", "gateway.routes.ledger-base-url");
    }

    private static String normalize(String value, String fallback, String name) {
        String candidate = value == null || value.isBlank() ? fallback : value;
        URI uri = URI.create(candidate);
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
            throw new IllegalArgumentException(name + " must be an HTTP(S) base URL");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(name + " must not carry a query or fragment");
        }
        return candidate.endsWith("/") ? candidate.substring(0, candidate.length() - 1) : candidate;
    }
}
