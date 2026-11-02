package com.dutch.ledger.safety;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/privacy")
public class PrivacyExportController {

    private final PrivacyExportService export;

    public PrivacyExportController(PrivacyExportService export) {
        this.export = export;
    }

    @GetMapping("/export")
    ResponseEntity<Map<String, Object>> export(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(Map.of("data", export.export(jwt.getSubject())));
    }
}
