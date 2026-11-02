package com.dutch.notification.device;

import java.util.Optional;

public interface MemberResolver {
    Optional<String> resolve(String appSubject);
}
