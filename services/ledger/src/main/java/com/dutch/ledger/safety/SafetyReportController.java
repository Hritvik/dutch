package com.dutch.ledger.safety;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
public class SafetyReportController {

    private final SafetyReportService reports;

    public SafetyReportController(SafetyReportService reports) {
        this.reports = reports;
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> report(@AuthenticationPrincipal Jwt jwt,
                                               @RequestBody SafetyReportService.CreateReportRequest request) {
        return ResponseEntity.ok(Map.of("data", reports.report(jwt.getSubject(), request)));
    }
}
