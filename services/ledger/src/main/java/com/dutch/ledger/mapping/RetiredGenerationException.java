package com.dutch.ledger.mapping;

/** A live association report resumed a generation UMS had already erased. */
public class RetiredGenerationException extends RuntimeException {

    public RetiredGenerationException() {
        super("association report reuses a retired generation");
    }
}
