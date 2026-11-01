package com.dutch.ledger.group;

import java.util.Optional;

/** Resolves the authenticated App Subject to a canonical Member from the local projection. */
public interface MemberResolver {
    Optional<MemberId> resolve(String appSubject);
}
