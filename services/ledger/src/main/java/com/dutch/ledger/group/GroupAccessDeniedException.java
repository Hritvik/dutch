package com.dutch.ledger.group;

/** The actor may not see or change the scope; the API layer maps this to 404. */
public class GroupAccessDeniedException extends GroupRuleException {
    public GroupAccessDeniedException() {
        super("The requested scope is unavailable.");
    }
}
