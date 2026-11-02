package com.dutch.notification.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Development transport used when no push webhook is configured. */
public class LoggingPushSender implements PushSender {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingPushSender.class);

    @Override
    public void send(PushMessage message) {
        LOG.info("push queued for delivery {}", message.deliveryId());
    }
}
