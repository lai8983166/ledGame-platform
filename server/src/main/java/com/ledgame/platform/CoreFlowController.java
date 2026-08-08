package com.ledgame.platform;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
@CrossOrigin(originPatterns = {"http://127.0.0.1:[*]", "http://localhost:[*]"})
public class CoreFlowController {
    private final JdbcTemplate jdbc;

    public CoreFlowController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "database", "sqlite");
    }

    @GetMapping("/members")
    public List<Map<String, Object>> findMembers(@RequestParam(required = false, defaultValue = "") String phone) {
        String normalizedPhone = normalizePhone(phone);
        if (normalizedPhone.isEmpty()) {
            return jdbc.queryForList("SELECT id, phone, name, avatar_id AS avatarId, birthday, gender, status, created_at AS createdAt FROM members WHERE status = 'ACTIVE' ORDER BY id DESC");
        }
        return jdbc.queryForList("SELECT id, phone, name, avatar_id AS avatarId, birthday, gender, status, created_at AS createdAt FROM members WHERE phone = ? AND status = 'ACTIVE'", normalizedPhone);
    }

    @PostMapping("/members")
    public Map<String, Object> createMember(@RequestBody MemberRequest request) {
        String phone = normalizePhone(request.phone());
        if (!phone.matches("\\d{7,15}")) throw badRequest("手机号格式不正确");
        if (request.name() == null || request.name().trim().length() < 2) throw badRequest("会员姓名至少需要 2 个字符");
        if (!jdbc.queryForList("SELECT id FROM members WHERE phone = ? AND status = 'ACTIVE'", phone).isEmpty()) throw conflict("该手机号已经注册");
        String now = now();
        jdbc.update("INSERT INTO members(phone, name, avatar_id, birthday, gender, status, created_at, updated_at, created_by) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)", phone, request.name().trim(), request.avatarId(), request.birthday(), request.gender(), now, now, request.createdBy() == null ? "kiosk" : request.createdBy());
        return findMembers(phone).get(0);
    }

    @GetMapping("/wristbands")
    public List<Map<String, Object>> listWristbands() {
        return jdbc.queryForList("SELECT w.card_uid AS uid, w.status, w.duration_minutes AS durationMinutes, w.charged_at AS chargedAt, b.member_id AS memberId, m.phone, m.name AS memberName FROM wristbands w LEFT JOIN wristband_bindings b ON b.wristband_id = w.id AND b.status IN ('READY', 'ACTIVE') LEFT JOIN members m ON m.id = b.member_id ORDER BY w.card_uid");
    }

    @GetMapping("/wristbands/{uid}")
    public Map<String, Object> getWristband(@PathVariable String uid) {
        return findWristband(uid);
    }

    @PostMapping("/wristbands/charge")
    @Transactional
    public Map<String, Object> charge(@RequestBody ChargeRequest request) {
        String uid = normalizeUid(request.uid());
        if (request.durationMinutes() == null || request.durationMinutes() < 1 || request.durationMinutes() > 1440) throw badRequest("购买分钟数必须是 1 到 1440 的整数");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, status FROM wristbands WHERE card_uid = ?", uid);
        String now = now();
        if (rows.isEmpty()) {
            jdbc.update("INSERT INTO wristbands(card_uid, status, duration_minutes, charged_at, created_at, updated_at) VALUES (?, 'CHARGED', ?, ?, ?, ?)", uid, request.durationMinutes(), now, now, now);
        } else {
            String status = String.valueOf(rows.get(0).get("status"));
            if (!status.equals("IN_STOCK")) throw conflict("该手环当前状态为 " + status + "，不能重复充时");
            jdbc.update("UPDATE wristbands SET status='CHARGED', duration_minutes=?, charged_at=?, updated_at=? WHERE card_uid=?", request.durationMinutes(), now, now, uid);
        }
        return findWristband(uid);
    }

    @PostMapping("/wristbands/bind")
    @Transactional
    public Map<String, Object> bind(@RequestBody BindRequest request) {
        String uid = normalizeUid(request.uid());
        if (request.memberId() == null) throw badRequest("缺少会员 ID");
        Map<String, Object> wristband = findWristband(uid);
        if (!String.valueOf(wristband.get("status")).equals("CHARGED")) {
            if (String.valueOf(wristband.get("status")).equals("READY") || String.valueOf(wristband.get("status")).equals("ACTIVE")) throw conflict("此手环已绑定");
            throw conflict("该手环当前不能绑定，状态为 " + wristband.get("status"));
        }
        Integer memberCount = jdbc.queryForObject("SELECT COUNT(*) FROM members WHERE id = ? AND status = 'ACTIVE'", Integer.class, request.memberId());
        if (memberCount == null || memberCount == 0) throw badRequest("会员不存在或已冻结");
        Number wristbandId = (Number) wristband.get("id");
        String now = now();
        jdbc.update("INSERT INTO wristband_bindings(wristband_id, member_id, status, duration_minutes, bound_at) VALUES (?, ?, 'READY', ?, ?)", wristbandId.longValue(), request.memberId(), wristband.get("durationMinutes"), now);
        jdbc.update("UPDATE wristbands SET status='READY', updated_at=? WHERE id=?", now, wristbandId.longValue());
        return findWristband(uid);
    }

    @PostMapping("/wristbands/clear")
    @Transactional
    public Map<String, Object> clearBalance(@RequestBody UidRequest request) {
        String uid = normalizeUid(request.uid());
        Map<String, Object> wristband = findWristband(uid);
        if (!String.valueOf(wristband.get("status")).equals("CHARGED")) throw conflict("只有未绑定的已充时手环可以清除可用余额");
        jdbc.update("UPDATE wristbands SET status='IN_STOCK', duration_minutes=NULL, charged_at=NULL, updated_at=? WHERE card_uid=?", now(), uid);
        return findWristband(uid);
    }

    @PostMapping("/wristbands/unbind")
    @Transactional
    public Map<String, Object> unbind(@RequestBody UidRequest request) {
        String uid = normalizeUid(request.uid());
        Map<String, Object> wristband = findWristband(uid);
        if (!String.valueOf(wristband.get("status")).equals("READY")) throw conflict("只有待游戏手环可以解除绑定");
        Number wristbandId = (Number) wristband.get("id");
        String now = now();
        jdbc.update("UPDATE wristband_bindings SET status='RETURNED', ended_at=? WHERE wristband_id=? AND status='READY'", now, wristbandId.longValue());
        jdbc.update("UPDATE wristbands SET status='IN_STOCK', duration_minutes=NULL, charged_at=NULL, updated_at=? WHERE id=?", now, wristbandId.longValue());
        return findWristband(uid);
    }

    private Map<String, Object> findWristband(String rawUid) {
        String uid = normalizeUid(rawUid);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT w.id, w.card_uid AS uid, w.status, w.duration_minutes AS durationMinutes, w.charged_at AS chargedAt, b.member_id AS memberId, m.phone, m.name AS memberName FROM wristbands w LEFT JOIN wristband_bindings b ON b.wristband_id = w.id AND b.status IN ('READY', 'ACTIVE') LEFT JOIN members m ON m.id = b.member_id WHERE w.card_uid = ?", uid);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到该手环，请先在会员管理端充时");
        return rows.get(0);
    }

    private static String normalizeUid(String raw) {
        String uid = raw == null ? "" : raw.trim();
        if (!uid.matches("\\d{1,32}")) throw badRequest("手环 UID 必须是读卡器输出的数字字符串");
        return uid;
    }

    private static String normalizePhone(String raw) {
        return raw == null ? "" : raw.replaceAll("\\D", "");
    }

    private static String now() {
        return OffsetDateTime.now(ZoneOffset.UTC).toString();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record ChargeRequest(String uid, Integer durationMinutes) {}
    public record BindRequest(String uid, Long memberId) {}
    public record UidRequest(String uid) {}
    public record MemberRequest(String phone, String name, String avatarId, String birthday, String gender, String createdBy) {}
}
