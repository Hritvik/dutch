package com.dutch.ledger.expense;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups/{groupId}/expenses/{expenseId}/corrections")
public class CorrectionController {

    private final CorrectionService corrections;

    public CorrectionController(CorrectionService corrections) {
        this.corrections = corrections;
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> correct(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String groupId,
            @PathVariable String expenseId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody CreateExpenseRequest request) {
        return ResponseEntity.ok(Map.of("data", corrections.correct(
                jwt.getSubject(), groupId, expenseId, idempotencyKey, parseIfMatch(ifMatch), request)));
    }

    static long parseIfMatch(String value) {
        String normalized = value.trim();
        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        try {
            long revision = Long.parseLong(normalized);
            if (revision < 1) {
                throw new NumberFormatException();
            }
            return revision;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("If-Match must be a revision");
        }
    }
}
