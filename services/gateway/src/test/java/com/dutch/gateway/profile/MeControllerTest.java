package com.dutch.gateway.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

class MeControllerTest {

    private static final Jwt JWT = new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(60),
            Map.of("alg", "none"), Map.of("sub", "aps_test_0123456789012345678901"));

    @Test
    void a_ready_profile_with_an_active_mapping_renders_the_dutch_profile() {
        MeController controller = new MeController(
                profile("PROFILE_READY", "ACTIVE", "Ada"),
                subject -> Optional.of("mem_test_0123456789"));

        ResponseEntity<?> response = controller.me(JWT, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) response.getBody()).get("data"))
                .isEqualTo(Map.of("memberId", "mem_test_0123456789", "displayName", "Ada",
                        "profileState", "PROFILE_READY", "lifecycleState", "ACTIVE"));
    }

    @Test
    void a_ready_profile_without_a_mapping_is_forbidden() {
        MeController controller = new MeController(
                profile("PROFILE_READY", "ACTIVE", "Ada"),
                subject -> Optional.empty());

        ResponseEntity<?> response = controller.me(JWT, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(error(response).get("code")).isEqualTo("forbidden");
    }

    @Test
    void a_not_ready_profile_is_profile_required() {
        MeController controller = new MeController(
                profile("PROFILE_REQUIRED", "ACTIVE", "Ada"),
                subject -> Optional.of("mem_test_0123456789"));

        ResponseEntity<?> state = controller.profileState(JWT, null);
        ResponseEntity<?> me = controller.me(JWT, null);

        assertThat(data(state).get("state")).isEqualTo("PROFILE_REQUIRED");
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error(me).get("code")).isEqualTo("profile_required");
    }

    @Test
    void a_closed_profile_is_never_ready() {
        MeController controller = new MeController(
                profile("PROFILE_READY", "CLOSED", "Ada"),
                subject -> Optional.of("mem_test_0123456789"));

        assertThat(data(controller.profileState(JWT, null)).get("state"))
                .isEqualTo("PROFILE_REQUIRED");
    }

    @Test
    void a_missing_display_name_is_profile_required_rather_than_invented() {
        MeController controller = new MeController(
                profile("PROFILE_READY", "ACTIVE", null),
                subject -> Optional.of("mem_test_0123456789"));

        ResponseEntity<?> response = controller.me(JWT, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error(response).get("code")).isEqualTo("profile_required");
    }

    @Test
    void an_unavailable_ums_is_a_safe_503_with_correlation() {
        MeController controller = new MeController(
                new UmsProfileSource() {
                    @Override
                    public Status status(String bearerToken) {
                        throw new UnavailableException("down", null);
                    }

                    @Override
                    public Optional<String> displayName(String bearerToken) {
                        return Optional.empty();
                    }
                },
                subject -> Optional.of("mem_test_0123456789"));

        ResponseEntity<?> response = controller.me(JWT, "corr-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("X-Correlation-ID")).isEqualTo("corr-1");
        assertThat(error(response).get("code")).isEqualTo("service_unavailable");
    }

    private static Map<?, ?> data(ResponseEntity<?> response) {
        return (Map<?, ?>) ((Map<?, ?>) response.getBody()).get("data");
    }

    private static Map<?, ?> error(ResponseEntity<?> response) {
        return (Map<?, ?>) ((Map<?, ?>) response.getBody()).get("error");
    }

    private static UmsProfileSource profile(String readiness, String lifecycle, String name) {
        return new UmsProfileSource() {
            @Override
            public Status status(String bearerToken) {
                return new Status(readiness, lifecycle);
            }

            @Override
            public Optional<String> displayName(String bearerToken) {
                return Optional.ofNullable(name);
            }
        };
    }
}
