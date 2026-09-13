package com.ledgame.platform;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
@CrossOrigin(originPatterns = {"http://127.0.0.1:[*]", "http://localhost:[*]"})
public class CoreFlowController {
    private final JdbcTemplate jdbc;
    private final GameAccessService gameAccessService;
    private final Clock clock;
    private final ActivationService activation;
    private final StartupGate startupGate;
    private final ProtectedDataService protectedData;
    private final OperatorAuthorizationService authorization;
    private final AvatarStorageService avatars;

    public CoreFlowController(JdbcTemplate jdbc, GameAccessService gameAccessService, Clock clock,
            ActivationService activation, StartupGate startupGate, ProtectedDataService protectedData,
            OperatorAuthorizationService authorization, AvatarStorageService avatars) {
        this.jdbc = jdbc;
        this.gameAccessService = gameAccessService;
        this.clock = clock;
        this.activation = activation;
        this.startupGate = startupGate;
        this.protectedData = protectedData;
        this.authorization = authorization;
        this.avatars = avatars;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "database", "sqlite", "activated", activation.activated(),
                "businessReady", activation.activated() && startupGate.businessReady());
    }

    @GetMapping("/members")
    public List<Map<String, Object>> findMembers(@RequestParam(required = false, defaultValue = "") String phone) {
        String normalizedPhone = normalizePhone(phone);
        String projection = """
            WITH totals AS (
                SELECT m.id, m.phone, m.name, m.avatar_id AS avatarId, m.birthday, m.gender,
                       m.status, m.created_at AS createdAt,
                       COALESCE(SUM(CASE WHEN g.status='COMPLETED' THEN g.points_awarded ELSE 0 END), 0) AS pointsTotal
                  FROM members m
                  LEFT JOIN game_play_records g ON g.member_id=m.id
                 WHERE m.status='ACTIVE' AND m.deleted_at IS NULL
                 GROUP BY m.id
            )
            SELECT totals.*,
                   1 + (SELECT COUNT(*) FROM totals higher WHERE higher.pointsTotal > totals.pointsTotal) AS rank
              FROM totals
            """;
        List<Map<String, Object>> rows = normalizedPhone.isEmpty()
                ? jdbc.queryForList(projection + " ORDER BY id DESC")
                : jdbc.queryForList(projection + " WHERE id IN (SELECT id FROM members WHERE phone_lookup_hash=? OR (phone_lookup_hash IS NULL AND phone=?))",
                        protectedData.phoneLookupHash(normalizedPhone), normalizedPhone);
        return rows.stream().map(this::decryptMemberRow).toList();
    }

    @PostMapping("/members")
    @Transactional
    public Map<String, Object> createMember(
            @RequestBody MemberRequest request,
            @RequestAttribute(value = OperatorAuditInterceptor.OPERATOR_ATTRIBUTE, required = false)
            OperatorSnapshot operator) {
        String phone = normalizePhone(request.phone());
        if (!phone.matches("\\d{7,15}")) throw badRequest("手机号格式不正确");
        if (request.name() == null || request.name().trim().length() < 2) throw badRequest("会员姓名至少需要 2 个字符");
        String phoneHash = protectedData.phoneLookupHash(phone);
        if (!jdbc.queryForList("SELECT id FROM members WHERE (phone_lookup_hash = ? OR (phone_lookup_hash IS NULL AND phone=?)) AND status = 'ACTIVE' AND deleted_at IS NULL", phoneHash, phone).isEmpty()) throw conflict("该手机号已经注册");
        String now = now();
        String createdBy = operator == null
                ? (request.createdBy() == null ? "kiosk" : request.createdBy())
                : "operator:" + operator.username();
        String storedAvatarId = request.avatarImageBase64() == null || request.avatarImageBase64().isBlank()
                ? request.avatarId() : avatars.store(request.avatarImageBase64(), request.avatarImageMimeType());
        try {
            jdbc.update("INSERT INTO members(phone, phone_lookup_hash, name, avatar_id, birthday, gender, status, created_at, updated_at, created_by) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)",
                    protectedData.encryptField("members", "phone", phone), phoneHash,
                    protectedData.encryptField("members", "name", request.name().trim()),
                    protectedData.encryptField("members", "avatar_id", storedAvatarId),
                    protectedData.encryptField("members", "birthday", request.birthday()),
                    protectedData.encryptField("members", "gender", request.gender()), now, now, createdBy);
            return findMembers(phone).get(0);
        } catch (RuntimeException exception) {
            if (avatars.isUploaded(storedAvatarId)) avatars.delete(storedAvatarId);
            throw exception;
        }
    }

    @GetMapping("/members/{id}/avatar")
    public ResponseEntity<byte[]> readMemberAvatar(@PathVariable Long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT avatar_id FROM members WHERE id=? AND status='ACTIVE' AND deleted_at IS NULL", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会员不存在");
        String avatarId = protectedData.decryptField("members", "avatar_id", rows.get(0).get("avatar_id"));
        byte[] payload = avatars.read(avatarId);
        if (payload == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会员没有已上传头像");
        MediaType type = payload.length >= 2 && (payload[0] & 0xff) == 0xff && (payload[1] & 0xff) == 0xd8
                ? MediaType.IMAGE_JPEG : MediaType.IMAGE_PNG;
        return ResponseEntity.ok().contentType(type).cacheControl(org.springframework.http.CacheControl.noCache()).body(payload);
    }

    @DeleteMapping("/members/{id}")
    @Transactional
    public Map<String, Object> deleteMember(@PathVariable Long id,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        authorization.requireCapability(operatorId, OperatorCapability.MEMBER_DELETE);
        List<Map<String, Object>> members = jdbc.queryForList("""
            SELECT id, phone, name
              FROM members
             WHERE id=? AND status='ACTIVE' AND deleted_at IS NULL
            """, id);
        if (members.isEmpty()) {
            throw GameAccessService.error(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "会员不存在或已经删除");
        }
        Integer openBindings = jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM wristband_bindings
             WHERE member_id=? AND status IN ('READY', 'ACTIVE')
            """, Integer.class, id);
        if (openBindings != null && openBindings > 0) {
            throw GameAccessService.error(HttpStatus.CONFLICT, "MEMBER_HAS_OPEN_WRISTBAND", "该会员仍有待游戏或计时中的手环，请先解除绑定或完成手环生命周期");
        }
        Integer runningGames = jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM game_play_records
             WHERE member_id=? AND status='RUNNING'
            """, Integer.class, id);
        if (runningGames != null && runningGames > 0) {
            throw GameAccessService.error(HttpStatus.CONFLICT, "MEMBER_HAS_RUNNING_GAME", "该会员仍有运行中的游戏，请先结束游戏");
        }
        String deletedAt = now();
        int updated = jdbc.update("""
            UPDATE members
               SET status='FROZEN', deleted_at=?, updated_at=?
             WHERE id=? AND status='ACTIVE' AND deleted_at IS NULL
            """, deletedAt, deletedAt, id);
        if (updated == 0) {
            throw GameAccessService.error(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "会员不存在或已经删除");
        }
        Map<String, Object> member = decryptMemberRow(members.get(0));
        return Map.of(
                "id", member.get("id"),
                "phone", member.get("phone"),
                "name", member.get("name"),
                "status", "DELETED",
                "deletedAt", deletedAt);
    }

    @GetMapping("/wristbands")
    public List<Map<String, Object>> listWristbands() {
        return gameAccessService.listWristbands();
    }

    @GetMapping("/records/wristband-bindings")
    public List<Map<String, Object>> listWristbandBindingRecords(
            @RequestParam(required = false) String uid,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        authorization.requireCapability(operatorId, OperatorCapability.OPERATIONS_VIEW);
        String sql = """
            SELECT b.id, w.card_uid AS uid, b.member_id AS memberId,
                   m.phone, m.name AS memberName, b.status,
                   b.duration_minutes AS durationMinutes, b.bound_at AS boundAt,
                   b.started_at AS startedAt, b.ended_at AS endedAt
              FROM wristband_bindings b
              JOIN wristbands w ON w.id=b.wristband_id
              JOIN members m ON m.id=b.member_id
            """;
        if (uid != null) sql += " WHERE w.card_uid_lookup_hash=?";
        sql += " ORDER BY b.bound_at DESC, b.id DESC";
        List<Map<String, Object>> rows = uid == null ? jdbc.queryForList(sql)
                : jdbc.queryForList(sql.replace("w.card_uid_lookup_hash=?", "(w.card_uid_lookup_hash=? OR (w.card_uid_lookup_hash IS NULL AND w.card_uid=?))"),
                        protectedData.wristbandLookupHash(normalizeUid(uid)), normalizeUid(uid));
        return rows.stream().map(this::decryptBindingRow).toList();
    }

    @GetMapping("/records/wristband-charges")
    public List<Map<String, Object>> listWristbandChargeRecords(
            @RequestParam(required = false) String uid,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        authorization.requireCapability(operatorId, OperatorCapability.OPERATIONS_VIEW);
        String sql = """
            SELECT c.id, c.wristband_uid AS uid, c.duration_minutes AS durationMinutes,
                   unit_price_cents AS unitPriceCents, amount_cents AS amountCents,
                   c.charged_at AS chargedAt
              FROM wristband_charge_records c
              JOIN wristbands w ON w.id=c.wristband_id
            """;
        if (uid != null) sql += " WHERE w.card_uid_lookup_hash=?";
        sql += " ORDER BY c.charged_at DESC, c.id DESC";
        List<Map<String, Object>> rows = uid == null ? jdbc.queryForList(sql)
                : jdbc.queryForList(sql.replace("w.card_uid_lookup_hash=?", "(w.card_uid_lookup_hash=? OR (w.card_uid_lookup_hash IS NULL AND w.card_uid=?))"),
                        protectedData.wristbandLookupHash(normalizeUid(uid)), normalizeUid(uid));
        return rows.stream().map(this::decryptChargeRow).toList();
    }

    @GetMapping("/wristbands/{uid}")
    public Map<String, Object> getWristband(@PathVariable String uid) {
        return gameAccessService.getWristband(uid);
    }

    @PostMapping("/wristbands/charge")
    @Transactional
    public Map<String, Object> charge(@RequestBody ChargeRequest request,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        authorization.requireCapability(operatorId, OperatorCapability.WRISTBAND_MANAGE);
        String uid = normalizeUid(request.uid());
        if (request.durationMinutes() == null || request.durationMinutes() < 1 || request.durationMinutes() > 1440) throw badRequest("购买分钟数必须是 1 到 1440 的整数");
        String uidHash = protectedData.wristbandLookupHash(uid);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, status FROM wristbands WHERE card_uid_lookup_hash = ? OR (card_uid_lookup_hash IS NULL AND card_uid=?)",
                uidHash, uid);
        String now = now();
        if (rows.isEmpty()) {
            jdbc.update("INSERT INTO wristbands(card_uid, card_uid_lookup_hash, status, duration_minutes, charged_at, created_at, updated_at) VALUES (?, ?, 'CHARGED', ?, ?, ?, ?)",
                    protectedData.encryptField("wristbands", "card_uid", uid), uidHash,
                    request.durationMinutes(), now, now, now);
        } else {
            String status = String.valueOf(rows.get(0).get("status"));
            if (!status.equals("IN_STOCK")) throw conflict("该手环当前状态为 " + status + "，不能重复充时");
            jdbc.update("UPDATE wristbands SET status='CHARGED', duration_minutes=?, charged_at=?, updated_at=? WHERE card_uid_lookup_hash=?", request.durationMinutes(), now, now, uidHash);
        }
        Long wristbandId = jdbc.queryForObject(
                "SELECT id FROM wristbands WHERE card_uid_lookup_hash=? OR (card_uid_lookup_hash IS NULL AND card_uid=?)",
                Long.class, uidHash, uid);
        int unitPriceCents = 100;
        jdbc.update("""
            INSERT INTO wristband_charge_records(
                wristband_id, wristband_uid, duration_minutes,
                unit_price_cents, amount_cents, charged_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """, wristbandId, protectedData.encryptField("wristband_charge_records", "wristband_uid", uid), request.durationMinutes(), unitPriceCents,
                request.durationMinutes() * unitPriceCents, now);
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
        Integer memberCount = jdbc.queryForObject("SELECT COUNT(*) FROM members WHERE id = ? AND status = 'ACTIVE' AND deleted_at IS NULL", Integer.class, request.memberId());
        if (memberCount == null || memberCount == 0) throw badRequest("会员不存在或已冻结");
        Number wristbandId = (Number) wristband.get("id");
        String now = now();
        jdbc.update("INSERT INTO wristband_bindings(wristband_id, member_id, status, duration_minutes, bound_at) VALUES (?, ?, 'READY', ?, ?)", wristbandId.longValue(), request.memberId(), wristband.get("durationMinutes"), now);
        jdbc.update("UPDATE wristbands SET status='READY', updated_at=? WHERE id=?", now, wristbandId.longValue());
        return findWristband(uid);
    }

    @PostMapping("/wristbands/clear")
    @Transactional
    public Map<String, Object> clearBalance(@RequestBody UidRequest request,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        authorization.requireCapability(operatorId, OperatorCapability.WRISTBAND_MANAGE);
        String uid = normalizeUid(request.uid());
        Map<String, Object> wristband = findWristband(uid);
        if (!String.valueOf(wristband.get("status")).equals("CHARGED")) throw conflict("只有未绑定的已充时手环可以清除可用余额");
        jdbc.update("UPDATE wristbands SET status='IN_STOCK', duration_minutes=NULL, charged_at=NULL, updated_at=? WHERE card_uid_lookup_hash=?", now(), protectedData.wristbandLookupHash(uid));
        return findWristband(uid);
    }

    @PostMapping("/wristbands/reclaim")
    @Transactional
    public Map<String, Object> reclaim(@RequestBody UidRequest request,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        authorization.requireCapability(operatorId, OperatorCapability.WRISTBAND_MANAGE);
        String uid = normalizeUid(request.uid());
        Map<String, Object> wristband = gameAccessService.getWristband(uid);
        if (!String.valueOf(wristband.get("status")).equals("EXPIRED")) {
            throw conflict("只有已到期的手环可以回收");
        }
        jdbc.update("UPDATE wristbands SET status='IN_STOCK', duration_minutes=NULL, charged_at=NULL, updated_at=? WHERE card_uid_lookup_hash=? AND status='EXPIRED'", now(), protectedData.wristbandLookupHash(uid));
        return gameAccessService.getWristband(uid);
    }

    @PostMapping("/wristbands/unbind")
    @Transactional
    public Map<String, Object> unbind(@RequestBody UidRequest request,
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        authorization.requireCapability(operatorId, OperatorCapability.WRISTBAND_MANAGE);
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
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT w.id, w.card_uid AS uid, w.status, w.duration_minutes AS durationMinutes, w.charged_at AS chargedAt, b.member_id AS memberId, m.phone, m.name AS memberName FROM wristbands w LEFT JOIN wristband_bindings b ON b.wristband_id = w.id AND b.status IN ('READY', 'ACTIVE') LEFT JOIN members m ON m.id = b.member_id WHERE w.card_uid_lookup_hash = ? OR (w.card_uid_lookup_hash IS NULL AND w.card_uid=?)",
                protectedData.wristbandLookupHash(uid), uid);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到该手环，请先在会员管理端充时");
        return decryptWristbandRow(rows.get(0));
    }

    private Map<String, Object> decryptMemberRow(Map<String, Object> source) {
        java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>(source);
        decryptInto(row, "phone", "members", "phone");
        decryptInto(row, "name", "members", "name");
        decryptInto(row, "avatarId", "members", "avatar_id");
        if (row.get("id") != null && avatars.isUploaded(String.valueOf(row.get("avatarId")))) {
            row.put("avatarUrl", "/api/members/" + row.get("id") + "/avatar");
        }
        decryptInto(row, "birthday", "members", "birthday");
        decryptInto(row, "gender", "members", "gender");
        return row;
    }

    private Map<String, Object> decryptWristbandRow(Map<String, Object> source) {
        java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>(source);
        decryptInto(row, "uid", "wristbands", "card_uid");
        decryptInto(row, "phone", "members", "phone");
        decryptInto(row, "memberName", "members", "name");
        return row;
    }

    private Map<String, Object> decryptBindingRow(Map<String, Object> source) {
        return decryptWristbandRow(source);
    }

    private Map<String, Object> decryptChargeRow(Map<String, Object> source) {
        java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>(source);
        decryptInto(row, "uid", "wristband_charge_records", "wristband_uid");
        return row;
    }

    private void decryptInto(Map<String, Object> row, String key, String table, String column) {
        if (row.containsKey(key) && row.get(key) != null) {
            row.put(key, protectedData.decryptField(table, column, row.get(key)));
        }
    }

    private static String normalizeUid(String raw) {
        String uid = raw == null ? "" : raw.trim();
        if (!uid.matches("\\d{1,32}")) throw badRequest("手环 UID 必须是读卡器输出的数字字符串");
        return uid;
    }

    private static String normalizePhone(String raw) {
        return raw == null ? "" : raw.replaceAll("\\D", "");
    }

    private String now() {
        return clock.instant().toString();
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
    public record MemberRequest(String phone, String name, String avatarId, String birthday, String gender, String createdBy,
            String avatarImageBase64, String avatarImageMimeType) {}
}
