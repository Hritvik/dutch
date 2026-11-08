package com.dutch.ledger.group;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Resolves an app subject to the Dutch member that may act on Ledger data.
 *
 * <p>Fails closed: the mapping must be active <em>and</em> UMS must currently report
 * the profile as ready and unrestricted. A missing binding means UMS never reported,
 * or the report was lost to a provider outage; both deny access rather than assume
 * readiness.
 */
@Repository
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
                SELECT m.member_id
                FROM app_subject_member_mapping m
                JOIN ums_profile_binding b ON b.app_subject = m.app_subject
                WHERE m.app_subject = ?
                  AND m.lifecycle_state = 'ACTIVE'
                  AND b.profile_state = 'PROFILE_READY'
                  AND b.lifecycle_state = 'ACTIVE'
                """, String.class, appSubject);
        return members.stream().findFirst().map(MemberId::new);
    }

    @Override
    public Optional<MemberId> resolveForDataSubjectRights(String appSubject) {
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
