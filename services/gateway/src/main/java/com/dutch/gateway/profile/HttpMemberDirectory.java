package com.dutch.gateway.profile;

import com.dutch.gateway.routing.GatewayRouteProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Reads the Dutch member mapping from Ledger over the internal service token. */
@Component
public class HttpMemberDirectory implements MemberDirectory {

    private final RestClient client;
    private final String serviceToken;

    public HttpMemberDirectory(GatewayRouteProperties routes,
                               @Value("${dutch.gateway.ums-service-token:}") String serviceToken) {
        this.client = RestClient.builder()
                .baseUrl(routes.ledgerBaseUrl())
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(
                        java.net.http.HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(3)).build()))
                .build();
        this.serviceToken = serviceToken;
    }

    @Override
    public Optional<String> memberFor(String appSubject) {
        try {
            JsonNode body = client.get()
                    .uri(uri -> uri.path("/internal/members/by-subject")
                            .queryParam("appSubject", appSubject).build())
                    .header("X-Service-Token", serviceToken)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .retrieve()
                    .body(JsonNode.class);
            String memberId = body == null ? null : body.path("memberId").asText(null);
            return memberId == null || memberId.isBlank() ? Optional.empty() : Optional.of(memberId);
        } catch (RestClientResponseException response) {
            if (response.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            // A 401 means the shared secret is wrong and a 5xx means Ledger is sick;
            // neither is "no member", and neither should read as a permanent denial.
            throw new UmsProfileSource.UnavailableException("Ledger mapping read failed", response);
        } catch (org.springframework.web.client.ResourceAccessException failure) {
            throw new UmsProfileSource.UnavailableException("Ledger mapping read failed", failure);
        }
    }
}
