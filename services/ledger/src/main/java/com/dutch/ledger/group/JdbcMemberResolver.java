package com.dutch.ledger.group;

import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnBean(DataSource.class)
public class JdbcMemberResolver implements MemberResolver {

    private final JdbcTemplate jdbc;

    public JdbcMemberResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<MemberId> resolve(String appSubject) {
        if (appSubject == null || !appSubject.startsWith("aps_")) {
            return Optional.empty();
        }
        List<String> members = jdbc.queryForList("""
                SELECT member_id FROM app_subject_member_mapping
                WHERE app_subject = ? AND lifecycle_state = 'ACTIVE'
                """, String.class, appSubject);
        return members.stream().findFirst().map(MemberId::new);
    }
}
