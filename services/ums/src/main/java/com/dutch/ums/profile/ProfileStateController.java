package com.dutch.ums.profile;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public final class ProfileStateController {
    static final String APP = "dutch-development";
    private final MemberProfileRepository profiles;

    public ProfileStateController(MemberProfileRepository profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/me")
    ResponseEntity<Map<String, Object>> getProfile(Authentication authentication, HttpServletRequest request) {
        String correlationId = (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        String appSubject = authentication.getName();
        var profile = profiles.findByAppSubject(APP, appSubject);
        if (profile.isEmpty() || profile.orElseThrow().profileState() != ProfileState.PROFILE_READY
                || profile.orElseThrow().lifecycleState() != MemberLifecycleState.ACTIVE) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", Map.of("code", "profile_required", "message", "Complete your profile to continue.",
                            "correlationId", correlationId)));
        }
        var member = profile.orElseThrow();
        var data = new LinkedHashMap<String, Object>();
        data.put("memberId", member.memberId());
        data.put("displayName", member.displayName());
        data.put("profileState", member.profileState().name());
        data.put("lifecycleState", member.lifecycleState().name());
        return ResponseEntity.ok(Map.of("data", data));
    }
}
