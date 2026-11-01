package com.dutch.ledger.expense;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups/{groupId}/activity")
public class ActivityController {

    private final ActivityService activity;

    public ActivityController(ActivityService activity) {
        this.activity = activity;
    }

    @GetMapping
    ResponseEntity<Map<String, Object>> list(@AuthenticationPrincipal Jwt jwt,
                                             @PathVariable String groupId) {
        return ResponseEntity.ok(Map.of("data", activity.forGroup(jwt.getSubject(), groupId)));
    }
}
