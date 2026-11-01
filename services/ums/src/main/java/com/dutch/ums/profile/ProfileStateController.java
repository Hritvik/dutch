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

    @GetMapping("/me/profile-state")
    ResponseEntity<Map<String, Object>> getProfileState(Authentication authentication) {
        String appSubject = authentication.getName();
        var state = visibleState(profiles.findByAppSubject(APP, appSubject));
        var data = new LinkedHashMap<String, Object>();
        data.put("state", state.name());
        return ResponseEntity.ok(Map.of("data", data));
    }

    /** Readiness is only visible while the lifecycle is active and a display name exists. */
    private ProfileState visibleState(java.util.Optional<MemberProfile> profile) {
        if (profile.isEmpty()) {
            return ProfileState.PROFILE_REQUIRED;
        }
        var member = profile.orElseThrow();
        if (member.lifecycleState() != MemberLifecycleState.ACTIVE) {
            return ProfileState.PROFILE_REQUIRED;
        }
        if (member.profileState() == ProfileState.PROFILE_READY
                && (member.displayName() == null || member.displayName().isBlank())) {
            return ProfileState.PROFILE_REQUIRED;
        }
        return member.profileState();
    }

    private static String correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        return value == null ? "cor_unknown" : value.toString();
    }

    @GetMapping("/me")
    ResponseEntity<Map<String, Object>> getProfile(Authentication authentication, HttpServletRequest request) {
        String correlationId = correlationId(request);
        String appSubject = authentication.getName();
        var profile = profiles.findByAppSubject(APP, appSubject);
        if (profile.isEmpty() || profile.orElseThrow().profileState() != ProfileState.PROFILE_READY
                || profile.orElseThrow().lifecycleState() != MemberLifecycleState.ACTIVE
                || profile.orElseThrow().displayName() == null
                || profile.orElseThrow().displayName().isBlank()) {
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
