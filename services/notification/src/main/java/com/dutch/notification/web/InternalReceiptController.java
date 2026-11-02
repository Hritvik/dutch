package com.dutch.notification.web;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Provider delivery receipts close the push loop without touching finance. */
@RestController
@RequestMapping("/internal")
public class InternalReceiptController {

    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;
    private final String serviceToken;

    public InternalReceiptController(
            JdbcTemplate jdbc, MeterRegistry meters,
            @Value("${dutch.notification.receipt-token:}") String serviceToken) {
        this.jdbc = jdbc;
        this.meters = meters;
        if (serviceToken == null || serviceToken.isBlank()) {
            org.slf4j.LoggerFactory.getLogger(InternalReceiptController.class).warn(
                    "DUTCH_NOTIFICATION_RECEIPT_TOKEN is unset; this instance accepts no receipts");
        }
        this.serviceToken = serviceToken == null || serviceToken.isBlank()
                ? UUID.randomUUID().toString() : serviceToken;
    }

    @PostMapping("/receipts")
    ResponseEntity<Void> receipt(@RequestHeader(value = "X-Service-Token", required = false) String token,
                                 @RequestBody ReceiptRequest request) {
        if (token == null || !MessageDigest.isEqual(serviceToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).build();
        }
        if (request == null || request.deliveryId() == null
                || !request.deliveryId().matches("^not_[A-Za-z0-9_-]{16,128}$")) {
            return ResponseEntity.badRequest().build();
        }
        if (!"DELIVERED".equals(request.status()) && !"FAILED".equals(request.status())) {
            return ResponseEntity.badRequest().build();
        }
        int updated;
        if ("DELIVERED".equals(request.status())) {
            updated = jdbc.update("""
                    UPDATE notification_delivery
                    SET status = 'SENT', provider_message_id = ?, receipted_at = ?
                    WHERE delivery_id = ? AND status <> 'SENT'
                    """, request.providerMessageId(), Timestamp.from(Instant.now()),
                    request.deliveryId());
            if (updated > 0) {
                meters.counter("notification.receipts", "status", "delivered").increment();
            }
        } else {
            updated = jdbc.update("""
                    UPDATE notification_delivery
                    SET status = 'FAILED', attempts = attempts + 1, receipted_at = ?
                    WHERE delivery_id = ? AND status NOT IN ('SENT', 'FAILED')
                    """, Timestamp.from(Instant.now()), request.deliveryId());
            if (updated > 0) {
                meters.counter("notification.receipts", "status", "failed").increment();
            }
        }
        if (updated > 0) {
            return ResponseEntity.noContent().build();
        }
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM notification_delivery WHERE delivery_id = ?",
                Integer.class, request.deliveryId());
        return exists != null && exists > 0
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    public record ReceiptRequest(String deliveryId, String status, String providerMessageId) {
    }
}
