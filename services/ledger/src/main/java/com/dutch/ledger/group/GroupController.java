package com.dutch.ledger.group;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups")
public class GroupController {

    private final GroupService groups;

    public GroupController(GroupService groups) {
        this.groups = groups;
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(@AuthenticationPrincipal Jwt jwt,
                                               @RequestBody CreateGroupRequest request) {
        return ResponseEntity.ok(Map.of("data",
                groups.create(jwt.getSubject(), request.name(), request.currency())));
    }

    @GetMapping
    ResponseEntity<Map<String, Object>> list(@AuthenticationPrincipal Jwt jwt) {
        List<GroupView> groupsForMember = groups.list(jwt.getSubject());
        return ResponseEntity.ok(Map.of("data", groupsForMember));
    }

    @GetMapping("/{groupId}")
    ResponseEntity<Map<String, Object>> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String groupId) {
        return ResponseEntity.ok(Map.of("data", groups.get(jwt.getSubject(), groupId)));
    }

    @PostMapping("/{groupId}/members")
    ResponseEntity<Map<String, Object>> addMember(@AuthenticationPrincipal Jwt jwt, @PathVariable String groupId,
                                                  @RequestHeader(value = "If-Match", required = true) String ifMatch,
                                                  @RequestBody MembershipRequest request) {
        Long expected = expectedVersion(ifMatch);
        GroupView view = request.placeholder()
                ? groups.addPlaceholder(jwt.getSubject(), groupId, request.memberId(), request.role(), expected)
                : groups.invite(jwt.getSubject(), groupId, request.memberId(), request.role(), expected);
        return ResponseEntity.ok(Map.of("data", view));
    }

    @PostMapping("/{groupId}/members/{memberId}/activate")
    ResponseEntity<Map<String, Object>> activate(@AuthenticationPrincipal Jwt jwt, @PathVariable String groupId,
                                                 @PathVariable String memberId,
                                                 @RequestHeader(value = "If-Match", required = true) String ifMatch) {
        return ResponseEntity.ok(Map.of("data",
                groups.activate(jwt.getSubject(), groupId, memberId, expectedVersion(ifMatch))));
    }

    @DeleteMapping("/{groupId}/members/{memberId}")
    ResponseEntity<Map<String, Object>> deactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable String groupId,
                                                   @PathVariable String memberId,
                                                   @RequestHeader(value = "If-Match", required = true) String ifMatch) {
        return ResponseEntity.ok(Map.of("data",
                groups.deactivate(jwt.getSubject(), groupId, memberId, expectedVersion(ifMatch))));
    }

    @PatchMapping("/{groupId}/members/{memberId}")
    ResponseEntity<Map<String, Object>> changeRole(@AuthenticationPrincipal Jwt jwt, @PathVariable String groupId,
                                                   @PathVariable String memberId,
                                                   @RequestHeader(value = "If-Match", required = true) String ifMatch,
                                                   @RequestBody RoleRequest request) {
        return ResponseEntity.ok(Map.of("data",
                groups.changeRole(jwt.getSubject(), groupId, memberId, request.role(),
                        expectedVersion(ifMatch))));
    }

    @PostMapping("/{groupId}/archive")
    ResponseEntity<Map<String, Object>> archive(@AuthenticationPrincipal Jwt jwt, @PathVariable String groupId,
                                                @RequestHeader(value = "If-Match", required = true) String ifMatch) {
        return ResponseEntity.ok(Map.of("data",
                groups.archive(jwt.getSubject(), groupId, expectedVersion(ifMatch))));
    }

    @PostMapping("/{groupId}/reopen")
    ResponseEntity<Map<String, Object>> reopen(@AuthenticationPrincipal Jwt jwt, @PathVariable String groupId,
                                               @RequestHeader(value = "If-Match", required = true) String ifMatch) {
        return ResponseEntity.ok(Map.of("data",
                groups.reopen(jwt.getSubject(), groupId, expectedVersion(ifMatch))));
    }

    private static Long expectedVersion(String ifMatch) {
        if (ifMatch == null) {
            return null;
        }
        String value = ifMatch.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            long version = Long.parseLong(value);
            if (version < 0) {
                throw new NumberFormatException();
            }
            return version;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("If-Match must be an aggregate version");
        }
    }

    public record CreateGroupRequest(String name, String currency) {
    }

    public record MembershipRequest(String memberId, GroupRole role, boolean placeholder) {
    }

    public record RoleRequest(GroupRole role) {
    }
}
