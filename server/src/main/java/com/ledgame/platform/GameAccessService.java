package com.ledgame.platform;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GameAccessService {
    private static final String WRISTBAND_SQL = """
        SELECT w.id, w.card_uid AS uid, w.status, w.duration_minutes AS durationMinutes,
               w.charged_at AS chargedAt, b.id AS bindingId, b.member_id AS memberId,
               b.duration_minutes AS bindingDurationMinutes, b.bound_at AS boundAt,
               b.started_at AS startedAt, b.ended_at AS endedAt,
               m.phone, m.name AS memberName, m.status AS memberStatus
          FROM wristbands w
          LEFT JOIN wristband_bindings b ON b.wristband_id = w.id AND b.status IN ('READY', 'ACTIVE')
          LEFT JOIN members m ON m.id = b.member_id
         WHERE w.card_uid = ?
        """;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public GameAccessService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = PlatformApiException.class)
    public Map<String, Object> activate(String rawUid) {
        String uid = normalizeUid(rawUid);
        Map<String, Object> row = findRaw(uid);
        requireBound(row);
        requireActiveMember(row);

        String status = text(row.get("status"));
        if ("READY".equals(status)) {
            String now = now();
            int bindingUpdated = jdbc.update(
                "UPDATE wristband_bindings SET status='ACTIVE', started_at=? WHERE id=? AND status='READY'",
                now, number(row.get("bindingId")));
            if (bindingUpdated == 1) {
                jdbc.update("UPDATE wristbands SET status='ACTIVE', updated_at=? WHERE id=?", now, number(row.get("id")));
            }
            row = findRaw(uid);
        }

        Map<String, Object> decorated = decorate(row, true);
        return Map.of(
            "member", memberView(row),
            "access", accessView(decorated)
        );
    }

    @Transactional
    public Map<String, Object> getWristband(String rawUid) {
        return decorate(findRaw(normalizeUid(rawUid)), false);
    }

    @Transactional
    public List<Map<String, Object>> listWristbands() {
        return jdbc.queryForList("SELECT card_uid AS uid FROM wristbands ORDER BY card_uid").stream()
            .map(row -> getWristband(text(row.get("uid"))))
            .toList();
    }

    @Transactional(noRollbackFor = PlatformApiException.class)
    public Map<String, Object> requireActiveAccess(String rawUid) {
        Map<String, Object> row = findRaw(normalizeUid(rawUid));
        requireBound(row);
        requireActiveMember(row);
        if (!"ACTIVE".equals(text(row.get("status")))) {
            throw error(HttpStatus.CONFLICT, "WRISTBAND_NOT_ACTIVATED", "请先刷手环完成入场激活");
        }
        return decorate(row, true);
    }

    Map<String, Object> findRaw(String rawUid) {
        String uid = normalizeUid(rawUid);
        List<Map<String, Object>> rows = jdbc.queryForList(WRISTBAND_SQL, uid);
        if (rows.isEmpty()) {
            throw error(HttpStatus.NOT_FOUND, "WRISTBAND_NOT_FOUND", "未找到该手环，请先在会员管理端充时");
        }
        return rows.get(0);
    }

    Map<String, Object> decorate(Map<String, Object> source, boolean denyExpired) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>(source);
        String status = text(source.get("status"));
        Integer durationMinutes = nullableInt(source.get("bindingDurationMinutes"));
        if (durationMinutes == null) durationMinutes = nullableInt(source.get("durationMinutes"));

        result.put("durationMinutes", durationMinutes);
        result.put("expiresAt", null);
        result.put("remainingSeconds", durationMinutes == null ? 0 : durationMinutes.longValue() * 60L);

        if ("ACTIVE".equals(status)) {
            Instant startedAt = parseInstant(source.get("startedAt"));
            if (startedAt == null || durationMinutes == null) {
                throw error(HttpStatus.CONFLICT, "WRISTBAND_STATE_INVALID", "手环计时状态不完整，请联系店员处理");
            }
            Instant expiresAt = startedAt.plus(Duration.ofMinutes(durationMinutes));
            long remaining = Math.max(0, Duration.between(clock.instant(), expiresAt).getSeconds());
            result.put("startedAt", startedAt.toString());
            result.put("expiresAt", expiresAt.toString());
            result.put("remainingSeconds", remaining);
            if (!clock.instant().isBefore(expiresAt)) {
                expire(source, expiresAt.toString());
                result.put("status", "EXPIRED");
                if (denyExpired) {
                    throw error(HttpStatus.CONFLICT, "WRISTBAND_EXPIRED", "手环可用时间已用完，请先续费");
                }
            }
        } else if ("READY".equals(status)) {
            result.put("startedAt", null);
        } else if ("EXPIRED".equals(status) && denyExpired) {
            throw error(HttpStatus.CONFLICT, "WRISTBAND_EXPIRED", "手环可用时间已用完，请先续费");
        }
        return result;
    }

    private void requireBound(Map<String, Object> row) {
        String status = text(row.get("status"));
        if ("EXPIRED".equals(status)) {
            throw error(HttpStatus.CONFLICT, "WRISTBAND_EXPIRED", "手环可用时间已用完，请先续费");
        }
        if (!("READY".equals(status) || "ACTIVE".equals(status)) || row.get("bindingId") == null) {
            throw error(HttpStatus.CONFLICT, "WRISTBAND_NOT_BOUND", "该手环尚未绑定会员");
        }
    }

    private void requireActiveMember(Map<String, Object> row) {
        if (!"ACTIVE".equals(text(row.get("memberStatus")))) {
            throw error(HttpStatus.CONFLICT, "MEMBER_FROZEN", "会员已被冻结，无法开始游戏");
        }
    }

    private void expire(Map<String, Object> row, String endedAt) {
        jdbc.update("UPDATE wristband_bindings SET status='EXPIRED', ended_at=? WHERE id=? AND status='ACTIVE'", endedAt, number(row.get("bindingId")));
        jdbc.update("UPDATE wristbands SET status='EXPIRED', updated_at=? WHERE id=? AND status='ACTIVE'", now(), number(row.get("id")));
    }

    private static Map<String, Object> memberView(Map<String, Object> row) {
        LinkedHashMap<String, Object> member = new LinkedHashMap<>();
        member.put("id", row.get("memberId"));
        member.put("phone", row.get("phone"));
        member.put("name", row.get("memberName"));
        member.put("status", row.get("memberStatus"));
        return member;
    }

    private static Map<String, Object> accessView(Map<String, Object> row) {
        LinkedHashMap<String, Object> access = new LinkedHashMap<>();
        access.put("bindingId", row.get("bindingId"));
        access.put("uid", row.get("uid"));
        access.put("status", row.get("status"));
        access.put("durationMinutes", row.get("durationMinutes"));
        access.put("startedAt", row.get("startedAt"));
        access.put("expiresAt", row.get("expiresAt"));
        access.put("remainingSeconds", row.get("remainingSeconds"));
        return access;
    }

    static String normalizeUid(String raw) {
        String uid = raw == null ? "" : raw.trim();
        if (!uid.matches("\\d{1,32}")) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_WRISTBAND_UID", "手环 UID 必须是读卡器输出的数字字符串");
        }
        return uid;
    }

    private String now() {
        return clock.instant().toString();
    }

    private static Instant parseInstant(Object raw) {
        if (raw == null) return null;
        try {
            return Instant.parse(text(raw));
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static Integer nullableInt(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static PlatformApiException error(HttpStatus status, String code, String message) {
        return new PlatformApiException(status, code, message);
    }
}
