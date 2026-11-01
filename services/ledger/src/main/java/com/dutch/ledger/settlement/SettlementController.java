package com.dutch.ledger.settlement;

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
@RequestMapping("/api/v1/groups/{groupId}/settlements")
public class SettlementController {

    private final SettlementService settlements;

    public SettlementController(SettlementService settlements) {
        this.settlements = settlements;
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String groupId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CreateSettlementRequest request) {
        return ResponseEntity.ok(Map.of("data",
                settlements.create(jwt.getSubject(), groupId, idempotencyKey, request)));
    }

    @PostMapping("/{settlementId}/void")
    ResponseEntity<Map<String, Object>> voidSettlement(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String groupId,
            @PathVariable String settlementId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ResponseEntity.ok(Map.of("data",
                settlements.voidSettlement(jwt.getSubject(), groupId, settlementId, idempotencyKey)));
    }
}
