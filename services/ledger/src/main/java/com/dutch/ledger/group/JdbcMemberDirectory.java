package com.dutch.ledger.group;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcMemberDirectory implements MemberDirectory {

    private final JdbcTemplate jdbc;

    public JdbcMemberDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isActive(MemberId memberId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM app_subject_member_mapping
                    WHERE member_id = ? AND lifecycle_state = 'ACTIVE'
                )
                """, Boolean.class, memberId.value());
        return Boolean.TRUE.equals(exists);
    }
}
