package com.ledgame.platform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlayerInfoService {
    private final JdbcTemplate jdbc;
    private final GameAccessService accessService;
    private final ProtectedDataService protectedData;

    public PlayerInfoService(JdbcTemplate jdbc, GameAccessService accessService, ProtectedDataService protectedData) {
        this.jdbc = jdbc;
        this.accessService = accessService;
        this.protectedData = protectedData;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> find(String rawPhone, String rawWristbandUid) {
        boolean hasPhone = rawPhone != null;
        boolean hasWristbandUid = rawWristbandUid != null;
        if (hasPhone == hasWristbandUid) {
            throw GameAccessService.error(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_PLAYER_INFO_QUERY",
                    "Player Info query requires exactly one identifier");
        }
        if (hasPhone) {
            return findByPhone(rawPhone);
        }

        String uid = GameAccessService.normalizeUid(rawWristbandUid);
        List<Map<String, Object>> members = jdbc.queryForList("""
            SELECT m.phone
              FROM wristbands w
              JOIN wristband_bindings b ON b.wristband_id=w.id AND b.status IN ('READY', 'ACTIVE')
              JOIN members m ON m.id=b.member_id
             WHERE (w.card_uid_lookup_hash=? OR (w.card_uid_lookup_hash IS NULL AND w.card_uid=?))
               AND m.status='ACTIVE' AND m.deleted_at IS NULL
             ORDER BY b.id DESC
             LIMIT 1
            """, protectedData.wristbandLookupHash(uid), uid);
        if (members.isEmpty()) {
            throw GameAccessService.error(
                    HttpStatus.NOT_FOUND,
                    "PLAYER_NOT_FOUND",
                    "No active member matches the supplied wristband");
        }
        return findByPhone(protectedData.decryptField("members", "phone", members.get(0).get("phone")));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findByPhone(String rawPhone) {
        String phone = rawPhone == null ? "" : rawPhone.replaceAll("\\D", "");
        if (!phone.matches("\\d{7,15}")) {
            throw GameAccessService.error(HttpStatus.BAD_REQUEST, "INVALID_PHONE", "手机号格式不正确");
        }
        List<Map<String, Object>> members = jdbc.queryForList("""
            SELECT id, phone, name, avatar_id AS avatarId, birthday, gender, status,
                   created_at AS createdAt, created_by AS createdBy
              FROM members
             WHERE (phone_lookup_hash=? OR (phone_lookup_hash IS NULL AND phone=?))
               AND status='ACTIVE' AND deleted_at IS NULL
            """, protectedData.phoneLookupHash(phone), phone);
        if (members.isEmpty()) {
            throw GameAccessService.error(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND", "未找到该手机号对应的会员");
        }
        LinkedHashMap<String, Object> profile = new LinkedHashMap<>(members.get(0));
        profile.put("phone", protectedData.decryptField("members", "phone", profile.get("phone")));
        profile.put("name", protectedData.decryptField("members", "name", profile.get("name")));
        profile.put("avatarId", protectedData.decryptField("members", "avatar_id", profile.get("avatarId")));
        profile.put("birthday", protectedData.decryptField("members", "birthday", profile.get("birthday")));
        profile.put("gender", protectedData.decryptField("members", "gender", profile.get("gender")));
        long memberId = number(profile.get("id"));
        long total = value(jdbc.queryForObject("""
            SELECT COALESCE(SUM(points_awarded), 0)
              FROM game_play_records
             WHERE member_id=? AND status='COMPLETED'
            """, Long.class, memberId));
        long rank = 1 + value(jdbc.queryForObject("""
            SELECT COUNT(*) FROM (
                SELECT m.id, COALESCE(SUM(CASE WHEN g.status='COMPLETED' THEN g.points_awarded ELSE 0 END), 0) AS total
                  FROM members m
                  LEFT JOIN game_play_records g ON g.member_id=m.id
                 WHERE m.status='ACTIVE' AND m.deleted_at IS NULL
                 GROUP BY m.id
                HAVING total > ?
            ) ranked
            """, Long.class, total));

        List<Map<String, Object>> wristbands = jdbc.queryForList("""
            SELECT w.card_uid AS uid
              FROM wristbands w
              JOIN wristband_bindings b ON b.wristband_id=w.id
             WHERE b.member_id=? AND b.status IN ('READY', 'ACTIVE')
             ORDER BY b.id DESC
            """, memberId).stream()
            .map(row -> accessService.getWristbandReadOnly(
                    protectedData.decryptField("wristbands", "card_uid", row.get("uid"))))
            .toList();

        List<Map<String, Object>> recentPlays = jdbc.queryForList("""
            SELECT id, game_id AS gameId, game_name AS gameName, device_id AS deviceId,
                   room_id AS roomId, status, started_at AS startedAt, ended_at AS endedAt,
                   success, termination_reason AS terminationReason, raw_score AS rawScore,
                   points_awarded AS pointsAwarded, scoring_policy AS scoringPolicy
              FROM game_play_records
             WHERE member_id=?
             ORDER BY started_at DESC, id DESC
             LIMIT 10
            """, memberId).stream().map(PlayerInfoService::normalizePlay).toList();

        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("profile", profile);
        result.put("points", Map.of("total", total, "rank", rank));
        result.put("wristbands", wristbands);
        result.put("recentPlays", recentPlays);
        return result;
    }

    private static Map<String, Object> normalizePlay(Map<String, Object> row) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>(row);
        Object success = row.get("success");
        result.put("success", success == null ? null : number(success) != 0);
        return result;
    }

    private static long value(Long value) {
        return value == null ? 0 : value;
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }
}
