package com.dutch.gateway.profile;

import com.dutch.gateway.routing.CorrelationId;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Translates the canonical UMS profile into the Dutch-owned {@code /api/v1/me}
 * contract.
 *
 * <p>UMS owns readiness and display; Ledger owns the member mapping. The Gateway
 * composes them without inventing either: a ready profile with no active Dutch
 * mapping is forbidden, and a non-ready profile returns the documented 409.
 */
@RestController
public class MeController {

    private final UmsProfileSource profiles;
    private final MemberDirectory members;

    public MeController(UmsProfileSource profiles, MemberDirectory members) {
        this.profiles = profiles;
        this.members = members;
    }

    @GetMapping("/api/v1/me/profile-state")
    ResponseEntity<?> profileState(@AuthenticationPrincipal Jwt jwt,
                                   @RequestHeader(value = CorrelationId.HEADER, required = false)
                                   String correlationId) {
        String correlation = correlation(correlationId);
        UmsProfileSource.Status status;
        try {
            status = profiles.status("Bearer " + jwt.getTokenValue());
        } catch (UmsProfileSource.UnavailableException unavailable) {
            return unavailable(correlation);
        }
        String state = status.ready() ? "PROFILE_READY" : "PROFILE_REQUIRED";
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(CorrelationId.HEADER, correlation)
                .body(java.util.Map.of("data", java.util.Map.of("state", state)));
    }

    @GetMapping("/api/v1/me")
    ResponseEntity<?> me(@AuthenticationPrincipal Jwt jwt,
                         @RequestHeader(value = CorrelationId.HEADER, required = false)
                         String correlationId) {
        String correlation = correlation(correlationId);
        UmsProfileSource.Status status;
        try {
            status = profiles.status("Bearer " + jwt.getTokenValue());
        } catch (UmsProfileSource.UnavailableException unavailable) {
            return unavailable(correlation);
        }
        if (!status.ready()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .cacheControl(CacheControl.noStore())
                    .header(CorrelationId.HEADER, correlation)
                    .body(error("profile_required", "Complete your profile to continue.", correlation));
        }

        Optional<String> member;
        try {
            member = members.memberFor(jwt.getSubject());
        } catch (UmsProfileSource.UnavailableException unavailable) {
            return unavailable(correlation);
        }
        if (member.isEmpty()) {
            // A ready profile is not Dutch membership: without the mapping nothing in
            // Ledger is addressable.
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .cacheControl(CacheControl.noStore())
                    .header(CorrelationId.HEADER, correlation)
                    .body(error("forbidden", "This resource is unavailable.", correlation));
        }

        Optional<String> displayName;
        try {
            displayName = profiles.displayName("Bearer " + jwt.getTokenValue());
        } catch (UmsProfileSource.UnavailableException unavailable) {
            return unavailable(correlation);
        }
        if (displayName.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .cacheControl(CacheControl.noStore())
                    .header(CorrelationId.HEADER, correlation)
                    .body(error("profile_required", "Complete your profile to continue.", correlation));
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(CorrelationId.HEADER, correlation)
                .body(java.util.Map.of("data", java.util.Map.of(
                        "memberId", member.get(),
                        "displayName", displayName.get(),
                        "profileState", "PROFILE_READY",
                        "lifecycleState", status.lifecycle())));
    }

    private static ResponseEntity<?> unavailable(String correlation) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .header(CorrelationId.HEADER, correlation)
                .body(error("service_unavailable", "Try again shortly.", correlation));
    }

    private static java.util.Map<String, Object> error(String code, String message,
                                                       String correlation) {
        return java.util.Map.of("error", java.util.Map.of(
                "code", code, "message", message, "correlationId", correlation));
    }

    private static String correlation(String requested) {
        if (requested != null && requested.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")) {
            return requested;
        }
        return java.util.UUID.randomUUID().toString();
    }
}
