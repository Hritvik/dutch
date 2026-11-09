package com.dutch.gateway.profile;

import com.dutch.gateway.routing.GatewayRouteProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Reads the canonical UMS profile surfaces. UMS owns readiness and display; the
 * Gateway only translates them into the Dutch contract.
 */
@Component
public class HttpUmsProfileSource implements UmsProfileSource {

    private final RestClient client;

    public HttpUmsProfileSource(GatewayRouteProperties routes) {
        this.client = RestClient.builder()
                .baseUrl(routes.umsBaseUrl())
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(
                        java.net.http.HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(3)).build()))
                .build();
    }

    @Override
    public Status status(String bearerToken) {
        JsonNode body = get("/api/v1/profile/status", bearerToken);
        JsonNode data = body.path("data");
        JsonNode state = data.has("state") ? data.path("state") : data;
        String readiness = state.path("readiness").asText(null);
        String lifecycle = state.path("lifecycle").asText(null);
        if (readiness == null) {
            throw new UnavailableException("UMS status has no readiness", null);
        }
        // A status document that omits the lifecycle is a degraded or version-skewed
        // reply, not a ready profile: default to the restrictive value.
        return new Status(readiness, lifecycle == null ? "UNKNOWN" : lifecycle);
    }

    @Override
    public Optional<String> displayName(String bearerToken) {
        JsonNode data = get("/api/v1/profile", bearerToken).path("data");
        String name = data.path("fields").path("displayName").asText(null);
        return name == null || name.isBlank() ? Optional.empty() : Optional.of(name);
    }

    private JsonNode get(String path, String bearerToken) {
        try {
            JsonNode body = client.get()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, bearerToken)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null) {
                throw new UnavailableException("UMS profile read returned no body", null);
            }
            return body;
        } catch (RestClientResponseException | org.springframework.web.client.ResourceAccessException failure) {
            throw new UnavailableException("UMS profile read failed", failure);
        }
    }
}
