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
            @Value("${dutch.outbox.service-token:dev-service-token-change-me}") String serviceToken) {
        this.url = url;
        this.serviceToken = serviceToken;
        this.client = RestClient.create();
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
