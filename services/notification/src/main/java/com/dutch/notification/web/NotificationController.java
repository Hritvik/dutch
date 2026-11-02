package com.dutch.notification.web;

import com.dutch.notification.device.DeviceService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final DeviceService devices;

    public NotificationController(DeviceService devices) {
        this.devices = devices;
    }

    @PostMapping("/devices")
    ResponseEntity<Map<String, Object>> register(@AuthenticationPrincipal Jwt jwt,
                                                 @RequestBody DeviceRequest request) {
        return ResponseEntity.ok(Map.of("data", devices.register(jwt.getSubject(),
                request.deviceId(), request.platform(), request.pushToken())));
    }

    @GetMapping("/devices")
    ResponseEntity<Map<String, Object>> devices(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(Map.of("data", devices.devices(jwt.getSubject())));
    }

    @DeleteMapping("/devices/{deviceId}")
    ResponseEntity<Void> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable String deviceId) {
        devices.remove(jwt.getSubject(), deviceId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/preferences")
    ResponseEntity<Map<String, Object>> preferences(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(Map.of("data", devices.preferences(jwt.getSubject())));
    }

    @PutMapping("/preferences")
    ResponseEntity<Map<String, Object>> preferences(@AuthenticationPrincipal Jwt jwt,
                                                    @RequestBody PreferenceRequest request) {
        return ResponseEntity.ok(Map.of("data",
                devices.updatePreferences(jwt.getSubject(), request.activityPush(),
                        request.settlementPush())));
    }

    @DeleteMapping("/privacy")
    ResponseEntity<Void> deleteData(@AuthenticationPrincipal Jwt jwt) {
        devices.deleteMemberData(jwt.getSubject());
        return ResponseEntity.noContent().build();
    }

    public record DeviceRequest(String deviceId, String platform, String pushToken) {
    }

    public record PreferenceRequest(Boolean activityPush, Boolean settlementPush) {
    }
}
