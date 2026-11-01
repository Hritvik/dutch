package com.dutch.ledger.direct;

import com.dutch.ledger.expense.CreateExpenseRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/direct-expenses")
public class DirectExpenseController {

    private final DirectExpenseService expenses;

    public DirectExpenseController(DirectExpenseService expenses) {
        this.expenses = expenses;
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CreateExpenseRequest request) {
        return ResponseEntity.ok(Map.of("data",
                expenses.create(jwt.getSubject(), idempotencyKey, request)));
    }

    @GetMapping("/{expenseId}")
    ResponseEntity<Map<String, Object>> read(@AuthenticationPrincipal Jwt jwt,
                                             @PathVariable String expenseId) {
        return ResponseEntity.ok(Map.of("data", expenses.read(jwt.getSubject(), expenseId)));
    }

    @GetMapping("/{expenseId}/activity")
    ResponseEntity<Map<String, Object>> activity(@AuthenticationPrincipal Jwt jwt,
                                                 @PathVariable String expenseId) {
        return ResponseEntity.ok(Map.of("data", expenses.activity(jwt.getSubject(), expenseId)));
    }
}
