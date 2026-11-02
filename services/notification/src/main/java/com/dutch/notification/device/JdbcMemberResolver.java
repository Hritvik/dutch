package com.dutch.notification.device;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcMemberResolver implements MemberResolver {

    private final JdbcTemplate jdbc;

    public JdbcMemberResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> resolve(String appSubject) {
        return jdbc.query("""
                SELECT member_id FROM app_subject_member_mapping
                WHERE app_subject = ? AND lifecycle_state = 'ACTIVE'
                """, (result, index) -> result.getString("member_id"), appSubject)
                .stream().findFirst();
    }
}
