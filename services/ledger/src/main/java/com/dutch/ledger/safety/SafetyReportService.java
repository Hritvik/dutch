package com.dutch.ledger.safety;

import com.dutch.ledger.group.GroupAccessDeniedException;
import com.dutch.ledger.group.MemberResolver;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SafetyReportService {

    private final JdbcTemplate jdbc;
    private final MemberResolver members;

    public SafetyReportService(JdbcTemplate jdbc, MemberResolver members) {
        this.jdbc = jdbc;
        this.members = members;
    }

    @Transactional
    public Map<String, Object> report(String appSubject, CreateReportRequest request,
                                      String idempotencyKey) {
        String reporter = members.resolve(appSubject)
                .orElseThrow(GroupAccessDeniedException::new).value();
        if (request == null || request.category() == null) {
            throw new IllegalArgumentException("category is required");
        }
        String category = request.category();
        if (!java.util.Set.of("ABUSE", "HARASSMENT", "SPAM", "FRAUD", "OTHER").contains(category)) {
            throw new IllegalArgumentException("category is invalid");
        }
        String scopeType = request.scopeType() == null ? "OTHER" : request.scopeType();
        if (!java.util.Set.of("GROUP", "DIRECT_EXPENSE", "MEMBER", "OTHER").contains(scopeType)) {
            throw new IllegalArgumentException("scopeType is invalid");
        }
        if (request.details() == null || request.details().isBlank()
                || request.details().length() > 1000) {
            throw new IllegalArgumentException("details must be between 1 and 1000 characters");
        }
        if (request.scopeId() != null && request.scopeId().length() > 256) {
            throw new IllegalArgumentException("scopeId must be at most 256 characters");
        }
        if (idempotencyKey != null
                && (idempotencyKey.isBlank() || idempotencyKey.length() > 200)) {
            throw new IllegalArgumentException("Idempotency-Key is invalid");
        }
        if (idempotencyKey != null) {
            var existing = jdbc.query("""
                    SELECT report_id, status FROM safety_report
                    WHERE reporter_member_id = ? AND idempotency_key = ?
                    """, (result, index) -> Map.<String, Object>of(
                    "reportId", result.getString("report_id"),
                    "status", result.getString("status")), reporter, idempotencyKey);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
        }
        String reportId = "rep_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO safety_report
                    (report_id, reporter_member_id, category, scope_type, scope_id, details,
                     idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (reporter_member_id, idempotency_key) DO NOTHING
                """, reportId, reporter, category, scopeType, request.scopeId(),
                request.details(), idempotencyKey);
        if (idempotencyKey != null) {
            var raced = jdbc.query("""
                    SELECT report_id, status FROM safety_report
                    WHERE reporter_member_id = ? AND idempotency_key = ?
                    """, (result, index) -> Map.<String, Object>of(
                    "reportId", result.getString("report_id"),
                    "status", result.getString("status")), reporter, idempotencyKey);
            if (!raced.isEmpty()) {
                return raced.get(0);
            }
        }
        return Map.of("reportId", reportId, "status", "RECEIVED");
    }

    public record CreateReportRequest(String category, String scopeType, String scopeId,
                                      String details) {
    }
}
