package com.dutch.notification.delivery;

import java.time.Duration;
import java.util.Map;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Provider-agnostic push transport. The provider receives the delivery id and is
 * expected to call POST /internal/receipts with DELIVERED or FAILED.
 */
public class WebhookPushSender implements PushSender {

    private final RestClient client;
    private final String url;
    private final String token;

    public WebhookPushSender(String url, String token) {
        this.url = url;
        this.token = token;
        var http = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public void send(PushMessage message) {
        client.post()
                .uri(url)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .body(Map.of(
                        "deliveryId", message.deliveryId(),
                        "pushToken", message.pushToken(),
                        "title", message.title(),
                        "body", message.body()))
                .retrieve()
                .toBodilessEntity();
    }
}
