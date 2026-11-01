package com.dutch.iam.identity;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ConditionalOnProperty(name = "iam.identifier-protection.enabled", havingValue = "true")
class IdentityPersistenceConfiguration {
    @Bean
    Clock iamClock() { return Clock.systemUTC(); }

    @Bean
    JdbcIdentityLifecycleRepository identityLifecycleRepository(JdbcTemplate jdbc) {
        return new JdbcIdentityLifecycleRepository(jdbc);
    }

    @Bean
    IdentifierProtector identifierProtector(
            @Value("${iam.identifier-protection.lookup-key}") String lookupKey,
            @Value("${iam.identifier-protection.encryption-key}") String encryptionKey,
            @Value("${iam.identifier-protection.key-version}") int keyVersion) {
        return new IdentifierProtector(lookupKey.getBytes(StandardCharsets.UTF_8),
                encryptionKey.getBytes(StandardCharsets.UTF_8), keyVersion);
    }

    @Bean
    IdentityProvisioningService identityProvisioningService(JdbcIdentityLifecycleRepository repository,
            IdentifierProtector protector, Clock iamClock) {
        return new IdentityProvisioningService(repository, repository, protector, iamClock);
    }
}
