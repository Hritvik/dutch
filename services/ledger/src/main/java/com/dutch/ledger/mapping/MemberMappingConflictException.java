package com.dutch.ledger.mapping;

/** A reused event id carried a different payload; the transport must quarantine it. */
public class MemberMappingConflictException extends RuntimeException {
    public MemberMappingConflictException() {
        super("The mapping event id was reused with a different payload.");
    }
}
