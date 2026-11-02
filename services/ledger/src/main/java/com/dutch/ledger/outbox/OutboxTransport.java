package com.dutch.ledger.outbox;

/** Delivers one committed outbox event; duplicates are expected and consumer-deduped. */
public interface OutboxTransport {
    void send(OutboxEvent event);
}
