package com.dutch.notification.delivery;

import java.util.List;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded retry for failed pushes; deliveries remain visible for inspection. */
@Component
public class DeliveryRetrySweep {

    private static final int MAX_ATTEMPTS = 3;
    private static final int BATCH = 50;

    private final JdbcTemplate jdbc;
    private final PushSender push;
    private final MeterRegistry meters;

    public DeliveryRetrySweep(JdbcTemplate jdbc, PushSender push, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.push = push;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${dutch.notification.retry-delay-ms:60000}")
    public void scheduled() {
        try {
            retryOnce();
        } catch (RuntimeException ignored) {
            // The next pass retries; finance and Activity are unaffected.
        }
    }

    public int retryOnce() {
        List<Retry> retries = jdbc.query("""
                SELECT d.delivery_id, dev.push_token
                FROM notification_delivery d
                JOIN notification_device dev ON dev.device_id = d.device_id AND dev.active
                WHERE (d.status = 'FAILED' OR (d.status = 'QUEUED' AND d.created_at < now() - interval '2 minutes'))
                  AND d.receipted_at IS NULL AND d.attempts < ?
                ORDER BY d.created_at
                LIMIT ?
                """, (result, index) -> new Retry(
                result.getString("delivery_id"), result.getString("push_token")),
                MAX_ATTEMPTS, BATCH);
        int sent = 0;
        for (Retry retry : retries) {
            try {
                push.send(new PushSender.PushMessage(retry.deliveryId(), retry.pushToken(),
                        "Dutch", "Open Dutch to review the latest Activity."));
                jdbc.update("UPDATE notification_delivery SET status = 'SENT' WHERE delivery_id = ?",
                        retry.deliveryId());
                meters.counter("notification.deliveries", "status", "retried").increment();
                sent++;
            } catch (RuntimeException failure) {
                jdbc.update("""
                        UPDATE notification_delivery SET attempts = attempts + 1
                        WHERE delivery_id = ?
                        """, retry.deliveryId());
            }
        }
        return sent;
    }

    private record Retry(String deliveryId, String pushToken) {
    }
}
