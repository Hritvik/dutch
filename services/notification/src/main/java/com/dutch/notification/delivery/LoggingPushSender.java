package com.dutch.notification.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Development transport. Production replaces this with the push provider adapter. */
@Component
public class LoggingPushSender implements PushSender {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingPushSender.class);

    @Override
    public void send(String pushToken, String title, String body) {
        LOG.info("push queued for token ending {}", pushToken.substring(Math.max(0, pushToken.length() - 4)));
    }
}
