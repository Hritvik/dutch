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
@RequestMapping("/api/v1/groups/{groupId}/expenses")
public class ExpenseController {

    private final ExpenseService expenses;

    public ExpenseController(ExpenseService expenses) {
        this.expenses = expenses;
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String groupId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CreateExpenseRequest request) {
        return ResponseEntity.ok(Map.of("data",
                expenses.create(jwt.getSubject(), groupId, idempotencyKey, request)));
    }
}
