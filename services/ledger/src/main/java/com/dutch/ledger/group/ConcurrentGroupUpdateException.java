package com.dutch.ledger.group;

/** Another writer changed the group after this command loaded it; maps to 409. */
public class ConcurrentGroupUpdateException extends GroupRuleException {
    public ConcurrentGroupUpdateException() {
        super("The group changed since it was loaded.");
    }
}
