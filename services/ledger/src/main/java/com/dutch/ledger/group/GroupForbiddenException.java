package com.dutch.ledger.group;

/** The actor can see the group but may not perform this action; maps to 403. */
public class GroupForbiddenException extends GroupRuleException {
    public GroupForbiddenException() {
        super("The action is not permitted in this group.");
    }
}
