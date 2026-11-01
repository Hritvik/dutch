package com.dutch.ledger.group;

/** Read-only check that a Member exists in the local UMS projection. */
public interface MemberDirectory {
    boolean isActive(MemberId memberId);
}
