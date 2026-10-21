package com.ledgame.platform;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
               b.allocated_seconds AS allocatedSeconds, b.returned_seconds AS returnedSeconds,
               m.phone, m.name AS memberName, m.status AS memberStatus,
               m.deleted_at AS memberDeletedAt, m.time_balance_seconds AS accountSeconds,
               m.consumption_started_at AS consumptionStartedAt
          FROM wristbands w
          LEFT JOIN wristband_bindings b ON b.id=(SELECT MAX(b2.id) FROM wristband_bindings b2 WHERE b2.wristband_id=w.id AND b2.status IN ('READY','ACTIVE','EXPIRED'))
          LEFT JOIN members m ON m.id = b.member_id
         WHERE w.card_uid_lookup_hash = ? OR (w.card_uid_lookup_hash IS NULL AND w.card_uid = ?)
        """;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ProtectedDataService protectedData;

    public GameAccessService(JdbcTemplate jdbc, Clock clock, ProtectedDataService protectedData) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.protectedData = protectedData;
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
    public List<Map<String, Object>> activateLegacyBatch(List<String> rawUids) {
        if (rawUids == null || rawUids.isEmpty()) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "至少需要一只参与游戏的手环");
        }
        List<String> uids = rawUids.stream().map(GameAccessService::normalizeUid).toList();
        if (new LinkedHashSet<>(uids).size() != uids.size()) {
            throw error(HttpStatus.CONFLICT, "DUPLICATE_WRISTBAND", "同一只手环不能在一局中重复参与");
        }

        List<Map<String, Object>> rows = uids.stream().map(this::findRaw).toList();
        for (Map<String, Object> row : rows) {
            requireBound(row);
            requireActiveMember(row);
            Map<String, Object> access = decorate(row, true, false);
            if (number(access.get("remainingSeconds")) < 60) {
                throw error(HttpStatus.CONFLICT, "WRISTBAND_INSUFFICIENT_TIME", "手环剩余时间不足一分钟");
            }
        }

        String now = now();
        for (Map<String, Object> row : rows) {
            if (!"READY".equals(text(row.get("status")))) continue;
            int bindingUpdated = jdbc.update(
                    "UPDATE wristband_bindings SET status='ACTIVE', started_at=? WHERE id=? AND status='READY'",
                    now, number(row.get("bindingId")));
            if (bindingUpdated != 1) {
                throw error(HttpStatus.CONFLICT, "WRISTBAND_STATE_CHANGED", "手环状态已变化，请重新刷卡");
            }
            jdbc.update("UPDATE wristbands SET status='ACTIVE', updated_at=? WHERE id=? AND status='READY'",
                    now, number(row.get("id")));
        }

        return uids.stream().map(uid -> {
            Map<String, Object> current = findRaw(uid);
            Map<String, Object> access = decorate(current, true, false);
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("member", memberView(current));
            result.put("access", accessView(access));
            return (Map<String, Object>) result;
        }).toList();
    }

    @Transactional
    public Map<String, Object> getWristband(String rawUid) {
        return decorate(findRaw(normalizeUid(rawUid)), false);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getWristbandReadOnly(String rawUid) {
        return decorate(findRaw(normalizeUid(rawUid)), false, false);
    }

    @Transactional
    public List<Map<String, Object>> listWristbands() {
        return jdbc.queryForList("SELECT card_uid AS uid FROM wristbands ORDER BY id").stream()
            .map(row -> getWristband(protectedData.decryptField("wristbands", "card_uid", row.get("uid"))))
            .toList();
    }

    public Map<String, Object> memberAccount(long memberId) {
        Map<String, Object> row = jdbc.queryForMap("SELECT time_balance_seconds AS accountSeconds, consumption_started_at AS consumptionStartedAt FROM members WHERE id=?", memberId);
        long remaining = remainingSeconds(row);
        return Map.of("remainingSeconds", remaining, "consuming", false);
    }

    long remainingSeconds(Map<String, Object> row) {
        long seconds = row.get("accountSeconds") instanceof Number value ? value.longValue() : 0;
        return seconds;
    }

    @Transactional
    public Map<String,Object> recharge(long memberId, int minutes, Long amountCents, String requestId,
            OperatorAuthorizationService.AuthorizedOperator operator) {
        if (minutes < 1 || minutes > 1440) throw error(HttpStatus.BAD_REQUEST, "INVALID_DURATION", "充值分钟数必须是 1 到 1440 的整数");
        if (amountCents != null && (amountCents < 0 || amountCents > 100_000_000L)) throw error(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "收款金额无效");
        validateRequestId(requestId);
        List<Map<String,Object>> previous = jdbc.queryForList("SELECT member_id,duration_minutes,amount_cents FROM member_recharge_records WHERE request_id=?", requestId);
        if (!previous.isEmpty()) {
            Map<String,Object> saved = previous.get(0);
            Long savedAmount = saved.get("amount_cents") == null ? null : number(saved.get("amount_cents"));
            if (number(saved.get("member_id")) != memberId || number(saved.get("duration_minutes")) != minutes || !java.util.Objects.equals(savedAmount, amountCents))
                throw error(HttpStatus.CONFLICT, "RECHARGE_REQUEST_CONFLICT", "充值请求内容已变化，请重新办理");
            return memberAccount(memberId);
        }
        int changed = jdbc.update("UPDATE members SET time_balance_seconds=time_balance_seconds+?,updated_at=? WHERE id=? AND status='ACTIVE' AND deleted_at IS NULL", minutes*60L, now(), memberId);
        if (changed != 1) throw error(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "会员不存在或已停用");
        jdbc.update("INSERT INTO member_recharge_records(member_id,duration_minutes,amount_cents,request_id,charged_at,operator_id,operator_username,operator_display_name) VALUES (?,?,?,?,?,?,?,?)",
                memberId, minutes, amountCents, requestId, now(), operator.id(), operator.username(), operator.displayName());
        return memberAccount(memberId);
    }

    private static void validateRequestId(String requestId) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,100}")) throw error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_ID", "请求无效，请重新打开办理窗口");
    }

    @Transactional
    public Map<String, Object> issue(String rawUid, long memberId, int minutes, String requestId,
            OperatorAuthorizationService.AuthorizedOperator operator) {
        String uid = normalizeUid(rawUid);
        if (minutes < 1 || minutes > 1440) throw error(HttpStatus.BAD_REQUEST, "INVALID_DURATION", "本次发卡时长必须是 1 到 1440 的整数");
        validateRequestId(requestId);
        List<Map<String, Object>> previous = jdbc.queryForList("SELECT b.id, b.member_id, b.issued_credit_minutes, b.status, w.card_uid FROM wristband_bindings b JOIN wristbands w ON w.id=b.wristband_id WHERE b.issue_request_id=?", requestId);
        if (!previous.isEmpty()) {
            Map<String, Object> binding = previous.get(0);
            if (number(binding.get("member_id")) != memberId || number(binding.get("issued_credit_minutes")) != minutes
                    || !uid.equals(protectedData.decryptField("wristbands", "card_uid", binding.get("card_uid")))) {
                throw error(HttpStatus.CONFLICT, "ISSUE_REQUEST_CONFLICT", "发卡请求内容已经变化，请重新办理");
            }
            return issueReceipt(uid, number(binding.get("id")), memberId, text(binding.get("status")));
        }
        return bindAccount(uid, memberId, minutes, requestId, operator);
    }

    @Transactional
    public Map<String, Object> bindCharged(String rawUid, long memberId) {
        String uid = normalizeUid(rawUid);
        Map<String, Object> band = findRaw(uid);
        if (!"CHARGED".equals(band.get("status"))) {
            throw error(HttpStatus.CONFLICT, "WRISTBAND_ALREADY_BOUND", "此手环已绑定");
        }
        return bindAccount(uid, memberId, ((Number)band.get("durationMinutes")).intValue(), null, null);
    }

    private Map<String, Object> bindAccount(String uid, long memberId, int minutes, String requestId,
            OperatorAuthorizationService.AuthorizedOperator operator) {
        List<Map<String, Object>> members = jdbc.queryForList("SELECT id FROM members WHERE id=? AND status='ACTIVE' AND deleted_at IS NULL", memberId);
        if (members.isEmpty()) throw error(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "会员不存在或已冻结");
        if (!jdbc.queryForList("SELECT id FROM wristband_bindings WHERE member_id=? AND status IN ('READY','ACTIVE','EXPIRED')", memberId).isEmpty()) {
            throw error(HttpStatus.CONFLICT, "MEMBER_HAS_WRISTBAND", "此会员已发卡，请先还卡");
        }
        String hash = protectedData.wristbandLookupHash(uid), now = now();
        List<Map<String, Object>> bands = jdbc.queryForList("SELECT id,status,duration_minutes FROM wristbands WHERE card_uid_lookup_hash=? OR (card_uid_lookup_hash IS NULL AND card_uid=?)", hash, uid);
        if (bands.isEmpty()) {
            jdbc.update("INSERT INTO wristbands(card_uid,card_uid_lookup_hash,status,created_at,updated_at) VALUES (?,?,'IN_STOCK',?,?)", protectedData.encryptField("wristbands", "card_uid", uid), hash, now, now);
            bands = jdbc.queryForList("SELECT id,status,duration_minutes FROM wristbands WHERE card_uid_lookup_hash=?", hash);
        }
        Map<String, Object> band = bands.get(0);
        if (!List.of("IN_STOCK", "CHARGED").contains(text(band.get("status")))) throw error(HttpStatus.CONFLICT, "WRISTBAND_ALREADY_BOUND", "此手环已绑定，请先还卡");
        long bandId = number(band.get("id"));
        long pending = "CHARGED".equals(band.get("status")) ? number(band.get("duration_minutes")) * 60L : 0;
        long available = number(memberAccount(memberId).get("remainingSeconds")) + pending;
        long allocated = minutes * 60L;
        if (allocated <= 0 || allocated > available) throw error(HttpStatus.CONFLICT, "MEMBER_INSUFFICIENT_TIME", "会员余额不足，请先充值");
        jdbc.update("UPDATE members SET time_balance_seconds=?,consumption_started_at=NULL,updated_at=? WHERE id=?", available-allocated, now, memberId);
        jdbc.update("INSERT INTO wristband_bindings(wristband_id,member_id,status,duration_minutes,bound_at,started_at,allocated_seconds,issue_request_id,issued_credit_minutes,issued_by_operator_id) VALUES (?,?,'ACTIVE',?,?,?,?,?,?,?)",
                bandId, memberId, minutes, now, now, allocated, requestId, minutes, operator == null ? null : operator.id());
        long bindingId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        if (pending > 0) jdbc.update("UPDATE wristband_charge_records SET member_id=? WHERE id=(SELECT MAX(id) FROM wristband_charge_records WHERE wristband_id=?) AND member_id IS NULL", memberId, bandId);
        jdbc.update("UPDATE wristbands SET status='ACTIVE',duration_minutes=NULL,charged_at=NULL,updated_at=? WHERE id=?", now, bandId);
        return getWristband(uid);
    }

    private Map<String, Object> issueReceipt(String uid, long bindingId, long memberId, String status) {
        Map<String,Object> saved = jdbc.queryForMap("SELECT allocated_seconds,started_at,returned_seconds FROM wristband_bindings WHERE id=?", bindingId);
        return Map.of("uid", uid, "bindingId", bindingId, "memberId", memberId, "status", status,
                "remainingSeconds", "RETURNED".equals(status) ? 0L : allocationRemaining(saved.get("allocated_seconds"), saved.get("started_at")),
                "returnedSeconds", number(saved.get("returned_seconds")), "accountRemainingSeconds", memberAccount(memberId).get("remainingSeconds"));
    }

    @Transactional
    public Map<String, Object> returnCard(String rawUid, long expectedBindingId) {
        String uid = normalizeUid(rawUid);
        List<Map<String, Object>> bindings = jdbc.queryForList("SELECT b.id,b.member_id,b.status FROM wristband_bindings b JOIN wristbands w ON w.id=b.wristband_id WHERE b.id=? AND (w.card_uid_lookup_hash=? OR (w.card_uid_lookup_hash IS NULL AND w.card_uid=?))", expectedBindingId, protectedData.wristbandLookupHash(uid), uid);
        if (bindings.isEmpty()) throw error(HttpStatus.CONFLICT, "BINDING_CHANGED", "手环绑定已经变化，请重新刷卡");
        Map<String, Object> binding = bindings.get(0);
        long memberId = number(binding.get("member_id"));
        if ("RETURNED".equals(binding.get("status"))) return issueReceipt(uid, expectedBindingId, memberId, "RETURNED");
        Map<String, Object> current = findRaw(uid);
        if (current.get("bindingId") == null || number(current.get("bindingId")) != expectedBindingId) throw error(HttpStatus.CONFLICT, "BINDING_CHANGED", "手环绑定已经变化，请重新刷卡");
        if (!jdbc.queryForList("SELECT id FROM game_play_records WHERE member_id=? AND status='RUNNING'", memberId).isEmpty()) throw error(HttpStatus.CONFLICT, "MEMBER_HAS_RUNNING_GAME", "会员正在游戏中，请先结束游戏再还卡");
        long remaining = "EXPIRED".equals(binding.get("status")) ? 0L : allocationRemaining(current.get("allocatedSeconds"), current.get("startedAt"));
        String now = now();
        jdbc.update("UPDATE members SET time_balance_seconds=time_balance_seconds+?,updated_at=? WHERE id=?", remaining, now, memberId);
        jdbc.update("UPDATE wristband_bindings SET status='RETURNED',ended_at=?,returned_seconds=? WHERE id=?", now, remaining, expectedBindingId);
        jdbc.update("UPDATE wristbands SET status='IN_STOCK',duration_minutes=NULL,charged_at=NULL,updated_at=? WHERE id=?", now, number(current.get("id")));
        return issueReceipt(uid, expectedBindingId, memberId, "RETURNED");
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
        List<Map<String, Object>> rows = jdbc.queryForList(
                WRISTBAND_SQL, protectedData.wristbandLookupHash(uid), uid);
        if (rows.isEmpty()) {
            throw error(HttpStatus.NOT_FOUND, "WRISTBAND_NOT_FOUND", "未找到该手环，请先在会员管理端充时");
        }
        LinkedHashMap<String, Object> row = new LinkedHashMap<>(rows.get(0));
        row.put("uid", protectedData.decryptField("wristbands", "card_uid", row.get("uid")));
        if (row.get("phone") != null) {
            row.put("phone", protectedData.decryptField("members", "phone", row.get("phone")));
        }
        if (row.get("memberName") != null) {
            row.put("memberName", protectedData.decryptField("members", "name", row.get("memberName")));
        }
        return row;
    }

    Map<String, Object> decorate(Map<String, Object> source, boolean denyExpired) {
        return decorate(source, denyExpired, true);
    }

    private Map<String, Object> decorate(Map<String, Object> source, boolean denyExpired, boolean persistExpired) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>(source);
        String status = text(source.get("status"));
        Integer durationMinutes = nullableInt(source.get("bindingDurationMinutes"));
        if (durationMinutes == null) durationMinutes = nullableInt(source.get("durationMinutes"));

        result.put("durationMinutes", durationMinutes);
        result.put("expiresAt", null);
        long remaining = source.get("memberId") == null ? (durationMinutes == null ? 0 : durationMinutes.longValue() * 60L)
                : "READY".equals(status) ? number(source.get("allocatedSeconds")) : allocationRemaining(source.get("allocatedSeconds"), source.get("startedAt"));
        result.put("remainingSeconds", remaining);

        if ("ACTIVE".equals(status)) {
            Instant startedAt = parseInstant(source.get("startedAt"));
            if (remaining > 0 && (startedAt == null || durationMinutes == null)) {
                throw error(HttpStatus.CONFLICT, "WRISTBAND_STATE_INVALID", "手环计时状态不完整，请联系店员处理");
            }
            Instant expiresAt = startedAt == null ? clock.instant() : startedAt.plusSeconds(number(source.get("allocatedSeconds")));
            result.put("startedAt", startedAt == null ? source.get("startedAt") : startedAt.toString());
            result.put("expiresAt", expiresAt.toString());
            result.put("remainingSeconds", remaining);
            if (!clock.instant().isBefore(expiresAt)) {
                if (persistExpired) {
                    expire(source, expiresAt.toString());
                }
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
        if (!"ACTIVE".equals(text(row.get("memberStatus"))) || row.get("memberDeletedAt") != null) {
            throw error(HttpStatus.CONFLICT, "MEMBER_FROZEN", "会员已被冻结，无法开始游戏");
        }
    }

    private void expire(Map<String, Object> row, String endedAt) {
        jdbc.update("UPDATE wristband_bindings SET status='EXPIRED', ended_at=? WHERE id=? AND status='ACTIVE'", endedAt, number(row.get("bindingId")));
        jdbc.update("UPDATE wristbands SET status='EXPIRED', updated_at=? WHERE id=? AND status='ACTIVE'", now(), number(row.get("id")));
    }

    private long allocationRemaining(Object allocated, Object started) {
        long seconds = allocated instanceof Number value ? value.longValue() : 0L;
        Instant start = parseInstant(started);
        return start == null ? seconds : Math.max(0L, Duration.between(clock.instant(), start.plusSeconds(seconds)).getSeconds());
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
