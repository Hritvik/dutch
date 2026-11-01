package com.dutch.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.dutch.ledger.group.GroupRepository;
import com.dutch.ledger.group.JdbcGroupMembershipEventConsumer;
import com.dutch.ledger.group.JdbcGroupRepository;
import com.dutch.ledger.group.JdbcMemberResolver;
import com.dutch.ledger.group.MemberResolver;
import com.dutch.ledger.mapping.JdbcMemberMappingConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the real service against PostgreSQL so the JDBC beans, Flyway, and the
 * mapping/replay consumers are exercised together. The in-memory controller tests
 * cannot catch a missing production bean.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class LedgerApplicationDatabaseTest {

    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledger_context_test");

    @MockBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private GroupRepository groups;

    @Autowired
    private MemberResolver members;

    @Autowired
    private JdbcGroupMembershipEventConsumer replay;

    @Autowired
    private JdbcMemberMappingConsumer mapping;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Test
    void starts_with_the_jdbc_repository_resolver_and_consumers() {
        assertThat(groups).isInstanceOf(JdbcGroupRepository.class);
        assertThat(members).isInstanceOf(JdbcMemberResolver.class);
        assertThat(replay).isNotNull();
        assertThat(mapping).isNotNull();
    }
}
