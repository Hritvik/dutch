package com.dutch.notification.web;

import com.dutch.notification.delivery.NotificationDeliveryService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal")
public class InternalEventController {

    private final NotificationDeliveryService deliveries;
    private final String serviceToken;

    public InternalEventController(NotificationDeliveryService deliveries,
                                   @Value("${dutch.notification.service-token:}") String serviceToken) {
        this.deliveries = deliveries;
        // Never fall back to a shared constant: blank means this instance accepts no
        // producer until an operator configures the same secret on both sides.
        this.serviceToken = serviceToken == null || serviceToken.isBlank()
                ? java.util.UUID.randomUUID().toString() : serviceToken;
    }

    @PostMapping("/events")
    ResponseEntity<Void> consume(@RequestHeader("X-Service-Token") String token,
                                 @RequestBody JsonNode envelope) {
        if (token == null || !java.security.MessageDigest.isEqual(
                serviceToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).build();
        }
        var pending = deliveries.consume(envelope);
        deliveries.deliver(pending);
        return ResponseEntity.noContent().build();
    }
}
