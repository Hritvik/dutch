package com.dutch.ledger.outbox;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpOutboxTransport implements OutboxTransport {

    private final RestClient client;
    private final String url;
    private final String serviceToken;

    public HttpOutboxTransport(
            @Value("${dutch.outbox.notification-url:http://localhost:8088/internal/events}") String url,
            @Value("${dutch.outbox.service-token:}") String serviceToken) {
        this.url = url;
        // A blank token is random per process and therefore cannot be forged from
        // source; operators must configure the same value on both services.
        if (serviceToken == null || serviceToken.isBlank()) {
            org.slf4j.LoggerFactory.getLogger(HttpOutboxTransport.class).warn(
                    "DUTCH_OUTBOX_SERVICE_TOKEN is unset; this process cannot publish events");
        }
        this.serviceToken = serviceToken == null || serviceToken.isBlank()
                ? java.util.UUID.randomUUID().toString() : serviceToken;
        java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(3))
                .build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(java.time.Duration.ofSeconds(5));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public void send(OutboxEvent event) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", event.eventId().toString());
        envelope.put("eventType", event.eventType());
        envelope.put("schemaVersion", event.schemaVersion());
        envelope.put("producer", event.producer());
        envelope.put("aggregate", Map.of(
                "type", event.aggregateType(),
                "id", event.aggregateId(),
                "version", event.aggregateVersion()));
        envelope.put("correlationId", event.correlationId());
        envelope.put("causationId", event.causationId() == null ? null : event.causationId().toString());
        envelope.put("occurredAt", event.occurredAt().toString());
        envelope.put("payload", event.payload());
        client.post()
                .uri(url)
                .header("X-Service-Token", serviceToken)
                .header("Content-Type", "application/json")
                .body(envelope)
                .retrieve()
                .toBodilessEntity();
    }
}
