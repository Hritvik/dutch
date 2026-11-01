package com.dutch.ums.profile;

import java.time.Instant;

public record MemberProfile(String memberId, String appRegistrationId, String appSubject, String displayName,
                            ProfileState profileState, MemberLifecycleState lifecycleState, long mappingVersion,
                            Instant createdAt) {
    public MemberProfile {
        if (memberId == null || !memberId.matches("^mem_[A-Za-z0-9_-]{16,128}$")
                || appRegistrationId == null || !appRegistrationId.matches("^[a-z][a-z0-9-]{2,63}$")
                || appSubject == null || !appSubject.matches("^aps_[A-Za-z0-9_-]{22,128}$")
                || profileState == null || lifecycleState == null || mappingVersion < 1 || createdAt == null) {
            throw new IllegalArgumentException("member_profile_invalid");
        }
    }

    @Override public String toString() { return "MemberProfile[memberId=" + memberId + ",redacted]"; }
}
