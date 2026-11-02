package com.dutch.notification.delivery;

public interface PushSender {
    void send(String pushToken, String title, String body);
}
