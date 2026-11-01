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
                                                  @RequestBody MembershipRequest request) {
        GroupView view = request.placeholder()
                ? groups.addPlaceholder(jwt.getSubject(), groupId, request.memberId(), request.role())
                : groups.invite(jwt.getSubject(), groupId, request.memberId(), request.role());
        return ResponseEntity.ok(Map.of("data", view));
    }

    @PostMapping("/{groupId}/members/{memberId}/activate")
    ResponseEntity<Map<String, Object>> activate(@AuthenticationPrincipal Jwt jwt, @PathVariable String groupId,
                                                 @PathVariable String memberId) {
        return ResponseEntity.ok(Map.of("data", groups.activate(jwt.getSubject(), groupId, memberId)));
    }

    @DeleteMapping("/{groupId}/members/{memberId}")
    ResponseEntity<Map<String, Object>> deactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable String groupId,
                                                   @PathVariable String memberId) {
        return ResponseEntity.ok(Map.of("data", groups.deactivate(jwt.getSubject(), groupId, memberId)));
    }

    @PatchMapping("/{groupId}/members/{memberId}")
    ResponseEntity<Map<String, Object>> changeRole(@AuthenticationPrincipal Jwt jwt, @PathVariable String groupId,
                                                   @PathVariable String memberId, @RequestBody RoleRequest request) {
        return ResponseEntity.ok(Map.of("data",
                groups.changeRole(jwt.getSubject(), groupId, memberId, request.role())));
    }

    @PostMapping("/{groupId}/archive")
    ResponseEntity<Map<String, Object>> archive(@AuthenticationPrincipal Jwt jwt, @PathVariable String groupId) {
        return ResponseEntity.ok(Map.of("data", groups.archive(jwt.getSubject(), groupId)));
    }

    @PostMapping("/{groupId}/reopen")
    ResponseEntity<Map<String, Object>> reopen(@AuthenticationPrincipal Jwt jwt, @PathVariable String groupId) {
        return ResponseEntity.ok(Map.of("data", groups.reopen(jwt.getSubject(), groupId)));
    }

    public record CreateGroupRequest(String name, String currency) {
    }

    public record MembershipRequest(String memberId, GroupRole role, boolean placeholder) {
    }

    public record RoleRequest(GroupRole role) {
    }
}
