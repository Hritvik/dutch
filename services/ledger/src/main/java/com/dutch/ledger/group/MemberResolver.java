package com.dutch.ledger.group;

import java.util.Optional;

/** Resolves the authenticated App Subject to a canonical Member from the local projection. */
public interface MemberResolver {

    /**
     * Financial access. Requires an active mapping and a profile UMS currently reports
     * as ready; anything else fails closed.
     */
    Optional<MemberId> resolve(String appSubject);

    /**
     * Data-subject rights (export). A person exercising a legal right must not be
     * denied because UMS reports the profile as not-ready, closed, or pending
     * deletion; only the mapping and its active lifecycle are required here.
     */
    default Optional<MemberId> resolveForDataSubjectRights(String appSubject) {
        return resolve(appSubject);
    }
}
