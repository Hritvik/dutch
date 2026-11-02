package com.dutch.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dutch.notification.delivery.PushSender;
import com.dutch.notification.delivery.WebhookPushSender;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WebhookPushSenderTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void posts_the_delivery_id_and_token_for_receipt_correlation() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/push", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        var sender = new WebhookPushSender(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/push", "provider-token");

        sender.send(new PushSender.PushMessage(
                "not_012345678901234567", "push-token-123456", "Dutch", "Open Dutch."));

        assertThat(authorization.get()).isEqualTo("Bearer provider-token");
        assertThat(body.get()).contains("not_012345678901234567", "push-token-123456",
                "Open Dutch.");
    }

    @Test
    void a_provider_failure_is_surfaced_for_the_retry_sweep() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/push", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        var sender = new WebhookPushSender(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/push", "provider-token");

        assertThatThrownBy(() -> sender.send(new PushSender.PushMessage(
                "not_112345678901234567", "push-token-123456", "Dutch", "Open Dutch.")))
                .isInstanceOf(RuntimeException.class);
    }
}
