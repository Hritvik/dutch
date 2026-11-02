package com.dutch.ledger.sync;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ledger")
public class SyncController {

    private final SyncService sync;

    public SyncController(SyncService sync) {
        this.sync = sync;
    }

    @GetMapping("/sync")
    ResponseEntity<Map<String, Object>> sync(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return ResponseEntity.ok(Map.of("data", sync.sync(jwt.getSubject(), cursor, limit)));
    }

    @PostMapping("/sync-snapshots")
    ResponseEntity<Map<String, Object>> snapshot(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(Map.of("data", sync.snapshot(jwt.getSubject())));
    }

    @GetMapping("/sync-snapshots/{token}")
    ResponseEntity<Map<String, Object>> snapshot(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String token,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "200") String limit) {
        int parsed;
        try {
            parsed = Integer.parseInt(limit);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
        return ResponseEntity.ok(Map.of("data",
                sync.readSnapshot(jwt.getSubject(), token, cursor, parsed)));
    }
}
