package com.dutch.ledger.group;

/** Every Group must retain at least one ACTIVE Admin. */
public class LastAdminException extends GroupRuleException {
    public LastAdminException() {
        super("A group must retain at least one active Admin.");
    }
}
