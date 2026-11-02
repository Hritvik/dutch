package com.dutch.notification.device;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceService {

    private final JdbcTemplate jdbc;
    private final MemberResolver members;

    public DeviceService(JdbcTemplate jdbc, MemberResolver members) {
        this.jdbc = jdbc;
        this.members = members;
    }

    @Transactional
    public Map<String, Object> register(String appSubject, String deviceId, String platform,
                                        String pushToken) {
        String memberId = requireMember(appSubject);
        if (deviceId == null || !deviceId.matches("^dev_[A-Za-z0-9_-]{16,128}$")) {
            throw new IllegalArgumentException("deviceId is invalid");
        }
        if (!"ANDROID".equals(platform) && !"IOS".equals(platform)) {
            throw new IllegalArgumentException("platform must be ANDROID or IOS");
        }
        if (pushToken == null || pushToken.length() < 8 || pushToken.length() > 512) {
            throw new IllegalArgumentException("pushToken is invalid");
        }
        Integer ownedByAnother = jdbc.queryForObject("""
                SELECT count(*) FROM notification_device WHERE device_id = ? AND member_id <> ?
                """, Integer.class, deviceId, memberId);
        if (ownedByAnother != null && ownedByAnother > 0) {
            throw new IllegalArgumentException("that device belongs to another member");
        }
        jdbc.update("""
                INSERT INTO notification_device (device_id, member_id, platform, push_token)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (device_id) DO UPDATE SET
                    platform = excluded.platform, push_token = excluded.push_token,
                    active = true, updated_at = now()
                WHERE notification_device.member_id = excluded.member_id
                """, deviceId, memberId, platform, pushToken);
        jdbc.update("""
                INSERT INTO notification_preference (member_id) VALUES (?)
                ON CONFLICT (member_id) DO NOTHING
                """, memberId);
        try {
            return device(deviceId, memberId);
        } catch (org.springframework.dao.EmptyResultDataAccessException raced) {
            throw new IllegalArgumentException("that device belongs to another member");
        }
    }

    public List<Map<String, Object>> devices(String appSubject) {
        String memberId = requireMember(appSubject);
        return jdbc.query("""
                SELECT device_id, platform, active, created_at, updated_at
                FROM notification_device WHERE member_id = ? ORDER BY created_at
                """, (result, index) -> Map.of(
                "deviceId", result.getString("device_id"),
                "platform", result.getString("platform"),
                "active", result.getBoolean("active"),
                "createdAt", result.getTimestamp("created_at").toInstant().toString(),
                "updatedAt", result.getTimestamp("updated_at").toInstant().toString()), memberId);
    }

    @Transactional
    public void remove(String appSubject, String deviceId) {
        String memberId = requireMember(appSubject);
        jdbc.update("""
                UPDATE notification_device SET active = false, updated_at = now()
                WHERE device_id = ? AND member_id = ?
                """, deviceId, memberId);
    }

    public Map<String, Object> preferences(String appSubject) {
        String memberId = requireMember(appSubject);
        ensurePreference(memberId);
        return jdbc.queryForObject("""
                SELECT activity_push, settlement_push FROM notification_preference
                WHERE member_id = ?
                """, (result, index) -> Map.of(
                "activityPush", result.getBoolean("activity_push"),
                "settlementPush", result.getBoolean("settlement_push")), memberId);
    }

    @Transactional
    public Map<String, Object> updatePreferences(String appSubject, Boolean activityPush,
                                                 Boolean settlementPush) {
        String memberId = requireMember(appSubject);
        ensurePreference(memberId);
        jdbc.update("""
                UPDATE notification_preference
                SET activity_push = COALESCE(?, activity_push),
                    settlement_push = COALESCE(?, settlement_push), updated_at = now()
                WHERE member_id = ?
                """, activityPush, settlementPush, memberId);
        return preferences(appSubject);
    }

    /** Privacy deletion for notification-owned data. */
    @Transactional
    public void deleteMemberData(String appSubject) {
        String memberId = requireMember(appSubject);
        jdbc.update("DELETE FROM notification_delivery WHERE member_id = ?", memberId);
        jdbc.update("DELETE FROM notification_device WHERE member_id = ?", memberId);
        jdbc.update("DELETE FROM notification_preference WHERE member_id = ?", memberId);
    }

    private void ensurePreference(String memberId) {
        jdbc.update("""
                INSERT INTO notification_preference (member_id) VALUES (?)
                ON CONFLICT (member_id) DO NOTHING
                """, memberId);
    }

    private Map<String, Object> device(String deviceId, String memberId) {
        return jdbc.queryForObject("""
                SELECT device_id, platform, active
                FROM notification_device WHERE device_id = ? AND member_id = ?
                """, (result, index) -> Map.of(
                "deviceId", result.getString("device_id"),
                "platform", result.getString("platform"),
                "active", result.getBoolean("active")), deviceId, memberId);
    }

    private String requireMember(String appSubject) {
        return members.resolve(appSubject)
                .orElseThrow(() -> new IllegalArgumentException("unknown member"));
    }
}
