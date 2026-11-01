package com.dutch.ledger.group;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dutch.ledger.TestGroupConfiguration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GroupConcurrencyTest {

    private static final GroupId GROUP = new GroupId("grp_012345678901234567");
    private static final MemberId ADMIN = new MemberId("mem_012345678901234567");
    private static final MemberId MEMBER = new MemberId("mem_112345678901234567");
    private static final MemberId OTHER = new MemberId("mem_212345678901234567");
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    @Test
    void a_second_writer_at_the_same_version_is_rejected() {
        var repository = new TestGroupConfiguration.InMemoryGroupRepository();
        repository.saveNew(Group.create(GROUP, "Flat", "INR", ADMIN, NOW));

        Group first = repository.findById(GROUP).orElseThrow();
        Group second = repository.findById(GROUP).orElseThrow();
        first.invite(ADMIN, MEMBER, GroupRole.MEMBER, NOW);
        second.invite(ADMIN, OTHER, GroupRole.MEMBER, NOW);

        repository.save(first, 0);

        assertThatThrownBy(() -> repository.save(second, 0))
                .isInstanceOf(ConcurrentGroupUpdateException.class);
    }
}
