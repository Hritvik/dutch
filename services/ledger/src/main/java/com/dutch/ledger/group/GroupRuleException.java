package com.dutch.ledger.group;

/** A refused Group or Membership change; the API layer maps this to a conflict. */
public class GroupRuleException extends RuntimeException {
    public GroupRuleException(String message) {
        super(message);
    }
}
