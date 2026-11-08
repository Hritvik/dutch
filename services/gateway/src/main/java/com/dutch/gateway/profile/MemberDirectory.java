package com.dutch.gateway.profile;

import java.util.Optional;

/** Ledger-owned mapping lookup used to render the Dutch member profile. */
public interface MemberDirectory {

    Optional<String> memberFor(String appSubject);
}
