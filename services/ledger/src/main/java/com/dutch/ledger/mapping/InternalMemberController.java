package com.dutch.ledger.mapping;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal member lookup for the Dutch edge.
 *
 * <p>The Gateway needs the Dutch member id to render `/api/v1/me`, but the mapping
 * is Ledger-owned. This route requires only an active mapping: readiness is
 * enforced by the Gateway's own UMS status check and by {@code /api/v1/**} access.
 */
@RestController
@RequestMapping("/internal/members")
public class InternalMemberController {

    private static final Logger log = LoggerFactory.getLogger(InternalMemberController.class);
    private static final String HEADER = "X-Service-Token";

    private final JdbcTemplate jdbc;
    private final String serviceToken;

    public InternalMemberController(JdbcTemplate jdbc,
                                    @Value("${dutch.ums.service-token:}") String serviceToken) {
        this.jdbc = jdbc;
        if (serviceToken == null || serviceToken.isBlank()) {
            log.warn("DUTCH_UMS_SERVICE_TOKEN is unset; this instance serves no edge lookup");
        }
        this.serviceToken = serviceToken == null || serviceToken.isBlank()
                ? UUID.randomUUID().toString() : serviceToken;
    }

    @GetMapping("/by-subject")
    ResponseEntity<Map<String, String>> bySubject(
            @RequestHeader(value = HEADER, required = false) String token,
            @RequestParam("appSubject") String appSubject) {
        if (token == null || !MessageDigest.isEqual(
                serviceToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).build();
        }
        if (appSubject == null || !appSubject.startsWith("aps_")) {
            return ResponseEntity.badRequest().build();
        }
        Optional<String> member = jdbc.queryForList("""
                SELECT member_id FROM app_subject_member_mapping
                WHERE app_subject = ? AND lifecycle_state = 'ACTIVE'
                """, String.class, appSubject).stream().findFirst();
        return member
                .map(id -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(Map.of("memberId", id)))
                .orElseGet(() -> ResponseEntity.status(404).build());
    }
}
