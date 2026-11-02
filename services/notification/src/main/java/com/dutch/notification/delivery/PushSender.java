package com.dutch.notification.delivery;

/** A push attempt carrying the delivery id so provider receipts can be correlated. */
public interface PushSender {

    void send(PushMessage message);

    record PushMessage(String deliveryId, String pushToken, String title, String body) {
    }
}
