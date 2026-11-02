package com.dutch.notification.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

    public NotificationDeliveryService(JdbcTemplate jdbc, PushSender push) {
        this.jdbc = jdbc;
        this.push = push;
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
        Integer already = jdbc.queryForObject(
                "SELECT count(*) FROM inbox_event WHERE event_id = ?", Integer.class, eventId);
        if (already != null && already > 0) {
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
            insertInbox(eventId, eventType, producer);
            return List.of();
        }
        if (!payload.has("recipientMemberIds")) {
            throw new IllegalArgumentException("recipientMemberIds are required");
        }
        String category = eventType.contains("settlement") ? "SETTLEMENT" : "EXPENSE";
        boolean preferenceColumn = category.equals("SETTLEMENT") ? true : false;
        List<PendingDelivery> pending = new ArrayList<>();
        for (JsonNode recipient : payload.get("recipientMemberIds")) {
            String memberId = recipient.asText();
            if (!memberId.matches("^mem_[A-Za-z0-9_-]{16,128}$")) {
                continue;
            }
            boolean enabled = preference(memberId, category);
            if (!enabled) {
                insertDelivery(memberId, null, eventId, category, "SUPPRESSED");
                continue;
            }
            List<Device> devices = jdbc.query("""
                    SELECT device_id, push_token FROM notification_device
                    WHERE member_id = ? AND active ORDER BY created_at
                    """, (result, index) -> new Device(
                    result.getString("device_id"), result.getString("push_token")), memberId);
            if (devices.isEmpty()) {
                insertDelivery(memberId, null, eventId, category, "SKIPPED");
                continue;
            }
            String title = category.equals("SETTLEMENT") ? "Settlement recorded" : "Expense recorded";
            String body = "Open Dutch to review the latest Activity.";
            for (Device device : devices) {
                String deliveryId = insertDelivery(memberId, device.deviceId(), eventId, category,
                        "QUEUED");
                pending.add(new PendingDelivery(deliveryId, device.pushToken(), title, body));
            }
        }
        if (!insertInbox(eventId, eventType, producer)) {
            // A concurrent duplicate won the inbox insert; its deliveries are authoritative.
            return List.of();
        }
        return pending;
    }

    private boolean insertInbox(UUID eventId, String eventType, String producer) {
        try {
            jdbc.update("""
                    INSERT INTO inbox_event (event_id, event_type, producer, processed_at)
                    VALUES (?, ?, ?, ?)
                    """, eventId, eventType, producer, java.sql.Timestamp.from(Instant.now()));
            return true;
        } catch (DuplicateKeyException raced) {
            return false;
        }
    }

    public void deliver(List<PendingDelivery> pending) {
        for (PendingDelivery delivery : pending) {
            try {
                push.send(delivery.pushToken(), delivery.title(), delivery.body());
                jdbc.update("UPDATE notification_delivery SET status = 'SENT' WHERE delivery_id = ?",
                        delivery.deliveryId());
            } catch (RuntimeException failure) {
                jdbc.update("UPDATE notification_delivery SET status = 'FAILED' WHERE delivery_id = ?",
                        delivery.deliveryId());
            }
        }
    }

    /** A notification-owned privacy deletion; events remain deduplicated. */
    public void deleteRecipientData(String memberId) {
        jdbc.update("DELETE FROM notification_delivery WHERE member_id = ?", memberId);
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
        jdbc.update("""
                INSERT INTO notification_delivery
                    (delivery_id, member_id, device_id, event_id, category, status)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id, device_id) DO NOTHING
                """, deliveryId, memberId, deviceId, eventId, category, status);
        return deliveryId;
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
