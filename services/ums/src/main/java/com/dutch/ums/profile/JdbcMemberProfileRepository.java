package com.dutch.ums.profile;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMemberProfileRepository implements MemberProfileRepository {
    private final JdbcTemplate jdbc;

    public JdbcMemberProfileRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<MemberProfile> findByAppSubject(String appRegistrationId, String appSubject) {
        return jdbc.query("""
                SELECT member_id, app_registration_id, app_subject, display_name, profile_state,
                       lifecycle_state, mapping_version, created_at
                  FROM member_profile
                 WHERE app_registration_id = ? AND app_subject = ?
                """, (rs, row) -> new MemberProfile(
                        rs.getString("member_id"), rs.getString("app_registration_id"),
                        rs.getString("app_subject"), rs.getString("display_name"),
                        ProfileState.valueOf(rs.getString("profile_state")),
                        MemberLifecycleState.valueOf(rs.getString("lifecycle_state")),
                        rs.getLong("mapping_version"), rs.getTimestamp("created_at").toInstant()),
                appRegistrationId, appSubject).stream().findFirst();
    }
}
