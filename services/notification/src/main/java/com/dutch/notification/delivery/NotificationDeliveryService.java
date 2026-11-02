package com.dutch.notification.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes a committed Ledger event exactly once per event id and records one
 * delivery per active device, honouring the recipient's preferences. Push is a
 * separate step after the transaction, so a push failure never affects finance.
 */
@Service
public class NotificationDeliveryService {

    private final JdbcTemplate jdbc;
    private final PushSender push;
    private final MeterRegistry meters;

    public NotificationDeliveryService(JdbcTemplate jdbc, PushSender push, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.push = push;
        this.meters = meters;
    }

    @Transactional
    public List<PendingDelivery> consume(JsonNode envelope) {
        UUID eventId = UUID.fromString(text(envelope, "eventId"));
        String eventType = text(envelope, "eventType");
        String producer = text(envelope, "producer");
        JsonNode payload = envelope.get("payload");
        if (payload == null) {
            throw new IllegalArgumentException("payload is required");
        }
        String inserted = jdbc.query("""
                INSERT INTO inbox_event (event_id, event_type, producer, processed_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                RETURNING event_id::text
                """, (result, index) -> result.getString("event_id"), eventId, eventType,
                producer, java.sql.Timestamp.from(Instant.now())).stream().findFirst().orElse(null);
        if (inserted == null) {
            return List.of();
        }
        if ("ums.member.anonymized.v1".equals(eventType)
                || "iam.identity.deleted.v1".equals(eventType)) {
            String memberId = payload.path("memberId").asText();
            if (!memberId.matches("^mem_[A-Za-z0-9_-]{16,128}$")) {
                throw new IllegalArgumentException("memberId is invalid");
            }
            jdbc.update("DELETE FROM notification_delivery WHERE member_id = ?", memberId);
            jdbc.update("DELETE FROM notification_device WHERE member_id = ?", memberId);
            jdbc.update("DELETE FROM notification_preference WHERE member_id = ?", memberId);
            return List.of();
        }
        if (!payload.has("recipientMemberIds")) {
            throw new IllegalArgumentException("recipientMemberIds are required");
        }
        String category = eventType.startsWith("ledger.settlement")
                ? "SETTLEMENT"
                : eventType.startsWith("ledger.expense") ? "EXPENSE" : "OTHER";
        List<PendingDelivery> pending = new ArrayList<>();
        for (JsonNode recipient : payload.get("recipientMemberIds")) {
            String memberId = recipient.asText();
            if (!memberId.matches("^mem_[A-Za-z0-9_-]{16,128}$")) {
                continue;
            }
            boolean enabled = preference(memberId, category);
            if (!enabled) {
                if (insertDelivery(memberId, null, eventId, category, "SUPPRESSED") != null) {
                    meters.counter("notification.deliveries", "status", "suppressed").increment();
                }
                continue;
            }
            List<Device> devices = jdbc.query("""
                    SELECT device_id, push_token FROM notification_device
                    WHERE member_id = ? AND active ORDER BY created_at
                    """, (result, index) -> new Device(
                    result.getString("device_id"), result.getString("push_token")), memberId);
            if (devices.isEmpty()) {
                if (insertDelivery(memberId, null, eventId, category, "SKIPPED") != null) {
                    meters.counter("notification.deliveries", "status", "skipped").increment();
                }
                continue;
            }
            String title = category.equals("SETTLEMENT") ? "Settlement recorded" : "Expense recorded";
            String body = "Open Dutch to review the latest Activity.";
            for (Device device : devices) {
                String deliveryId = insertDelivery(memberId, device.deviceId(), eventId, category,
                        "QUEUED");
                if (deliveryId != null) {
                    meters.counter("notification.deliveries", "status", "queued").increment();
                    pending.add(new PendingDelivery(deliveryId, device.pushToken(), title, body));
                }
            }
        }
        return pending;
    }

    public void deliver(List<PendingDelivery> pending) {
        for (PendingDelivery delivery : pending) {
            try {
                push.send(new PushSender.PushMessage(delivery.deliveryId(), delivery.pushToken(),
                        delivery.title(), delivery.body()));
                jdbc.update("UPDATE notification_delivery SET status = 'SENT' WHERE delivery_id = ?",
                        delivery.deliveryId());
                meters.counter("notification.deliveries", "status", "sent").increment();
            } catch (RuntimeException failure) {
                jdbc.update("""
                        UPDATE notification_delivery
                        SET status = 'FAILED', attempts = attempts + 1
                        WHERE delivery_id = ?
                        """, delivery.deliveryId());
                meters.counter("notification.deliveries", "status", "failed").increment();
            }
        }
    }

    private boolean preference(String memberId, String category) {
        List<Boolean> values = jdbc.query("""
                SELECT activity_push, settlement_push FROM notification_preference
                WHERE member_id = ?
                """, (result, index) -> category.equals("SETTLEMENT")
                ? result.getBoolean("settlement_push") : result.getBoolean("activity_push"), memberId);
        return values.isEmpty() || values.get(0);
    }

    private String insertDelivery(String memberId, String deviceId, UUID eventId,
                                  String category, String status) {
        String deliveryId = "not_" + UUID.randomUUID().toString().replace("-", "");
        return jdbc.query("""
                INSERT INTO notification_delivery
                    (delivery_id, member_id, device_id, event_id, category, status)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id, device_id) DO NOTHING
                RETURNING delivery_id
                """, (result, index) -> result.getString("delivery_id"), deliveryId, memberId,
                deviceId, eventId, category, status).stream().findFirst().orElse(null);
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.asText();
    }

    private record Device(String deviceId, String pushToken) {
    }

    public record PendingDelivery(String deliveryId, String pushToken, String title, String body) {
    }
}
