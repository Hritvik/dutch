package com.dutch.ledger;

import com.dutch.ledger.group.Group;
import com.dutch.ledger.group.GroupId;
import com.dutch.ledger.group.GroupRepository;
import com.dutch.ledger.group.MemberId;
import com.dutch.ledger.group.MemberResolver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class TestGroupConfiguration {

    public static final String ADMIN_SUBJECT = "aps_admin_0123456789012345678901";
    public static final String MEMBER_SUBJECT = "aps_member_012345678901234567890";
    public static final String OUTSIDER_SUBJECT = "aps_outsider_01234567890123456789";
    public static final MemberId ADMIN = new MemberId("mem_012345678901234567");
    public static final MemberId MEMBER = new MemberId("mem_112345678901234567");
    public static final MemberId OUTSIDER = new MemberId("mem_212345678901234567");

    private static final Map<String, MemberId> MEMBERS = Map.of(
            ADMIN_SUBJECT, ADMIN,
            MEMBER_SUBJECT, MEMBER,
            OUTSIDER_SUBJECT, OUTSIDER);

    @Bean
    GroupRepository groupRepository() {
        return new InMemoryGroupRepository();
    }

    @Bean
    MemberResolver memberResolver() {
        return subject -> Optional.ofNullable(MEMBERS.get(subject));
    }

    static final class InMemoryGroupRepository implements GroupRepository {
        private final Map<GroupId, Group> groups = new ConcurrentHashMap<>();

        @Override
        public Optional<Group> findById(GroupId id) {
            return Optional.ofNullable(groups.get(id));
        }

        @Override
        public List<Group> findReadableBy(MemberId memberId) {
            return groups.values().stream().filter(group -> group.canRead(memberId)).toList();
        }

        @Override
        public void save(Group group) {
            groups.put(group.id(), group);
        }
    }
}
