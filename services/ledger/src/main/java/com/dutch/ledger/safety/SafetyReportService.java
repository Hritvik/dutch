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
        String requestHash = hash(category + "|" + scopeType + "|"
                + (request.scopeId() == null ? "" : request.scopeId()) + "|" + request.details());
        if (idempotencyKey != null) {
            var existing = jdbc.query("""
                    SELECT report_id, status, request_hash FROM safety_report
                    WHERE reporter_member_id = ? AND idempotency_key = ?
                    """, (result, index) -> new Object[]{
                    result.getString("report_id"), result.getString("status"),
                    result.getString("request_hash")}, reporter, idempotencyKey);
            if (!existing.isEmpty()) {
                if (!requestHash.equals(existing.get(0)[2])) {
                    throw new com.dutch.ledger.expense.IdempotencyConflictException();
                }
                return Map.of("reportId", existing.get(0)[0], "status", existing.get(0)[1]);
            }
        }
        String reportId = "rep_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO safety_report
                    (report_id, reporter_member_id, category, scope_type, scope_id, details,
                     idempotency_key, request_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (reporter_member_id, idempotency_key) DO NOTHING
                """, reportId, reporter, category, scopeType, request.scopeId(),
                request.details(), idempotencyKey, requestHash);
        if (idempotencyKey != null) {
            var raced = jdbc.query("""
                    SELECT report_id, status, request_hash FROM safety_report
                    WHERE reporter_member_id = ? AND idempotency_key = ?
                    """, (result, index) -> new Object[]{
                    result.getString("report_id"), result.getString("status"),
                    result.getString("request_hash")}, reporter, idempotencyKey);
            if (!raced.isEmpty()) {
                if (!requestHash.equals(raced.get(0)[2])) {
                    throw new com.dutch.ledger.expense.IdempotencyConflictException();
                }
                return Map.of("reportId", raced.get(0)[0], "status", raced.get(0)[1]);
            }
        }
        return Map.of("reportId", reportId, "status", "RECEIVED");
    }

    private static String hash(String canonical) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record CreateReportRequest(String category, String scopeType, String scopeId,
                                      String details) {
    }
}
