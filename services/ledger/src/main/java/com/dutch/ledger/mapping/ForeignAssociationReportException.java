package com.dutch.ledger.mapping;

/** A report minted for another app registration or environment. */
public class ForeignAssociationReportException extends RuntimeException {

    public ForeignAssociationReportException() {
        super("association report targets another app registration or environment");
    }
}
