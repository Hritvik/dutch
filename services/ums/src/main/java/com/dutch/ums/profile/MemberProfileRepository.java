package com.dutch.ums.profile;

import java.util.Optional;

public interface MemberProfileRepository {
    Optional<MemberProfile> findByAppSubject(String appRegistrationId, String appSubject);
}
