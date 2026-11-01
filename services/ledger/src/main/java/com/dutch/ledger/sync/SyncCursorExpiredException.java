package com.dutch.ledger.sync;

/** The presented cursor is missing, malformed, foreign, expired, or from another epoch. */
public class SyncCursorExpiredException extends RuntimeException {
    public SyncCursorExpiredException() {
        super("The sync cursor expired. Request a full snapshot.");
    }

    public SyncCursorExpiredException(Throwable cause) {
        super("The sync cursor expired. Request a full snapshot.", cause);
    }
}
