package com.dutch.notification.delivery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PushConfiguration {

    @Bean
    @ConditionalOnProperty(name = "dutch.push.webhook-url")
    PushSender webhookPushSender(
            @Value("${dutch.push.webhook-url}") String url,
            @Value("${dutch.push.webhook-token:}") String token) {
        return new WebhookPushSender(url, token);
    }

    @Bean
    @ConditionalOnMissingBean(PushSender.class)
    PushSender loggingPushSender() {
        return new LoggingPushSender();
    }
}
