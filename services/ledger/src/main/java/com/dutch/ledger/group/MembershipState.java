package com.dutch.ledger.group;

/**
 * App access and financial history are independent: INVITED and PLACEHOLDER
 * carry allocations without app access, ACTIVE collaborates, and INACTIVE
 * preserves read-only history and settleable balances.
 */
public enum MembershipState {
    INVITED,
    PLACEHOLDER,
    ACTIVE,
    INACTIVE
}
