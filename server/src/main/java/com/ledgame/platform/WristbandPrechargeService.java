package com.ledgame.platform;

import static com.ledgame.platform.GameAccessService.error;
import static com.ledgame.platform.GameAccessService.normalizeUid;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WristbandPrechargeService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ProtectedDataService protectedData;

    public WristbandPrechargeService(JdbcTemplate jdbc, Clock clock, ProtectedDataService protectedData) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.protectedData = protectedData;
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> list(String uid, String status) {
        if (uid != null && !uid.isBlank()) normalizeUid(uid);
        if (status != null && !List.of("PENDING", "CLAIMED", "CANCELLED").contains(status))
            throw error(HttpStatus.BAD_REQUEST,"INVALID_PRECHARGE_STATUS","预充值状态无效");
        return jdbc.queryForList("""
            SELECT c.id,c.wristband_uid AS uid,c.duration_minutes AS durationMinutes,
                   COALESCE(c.precharge_status,'PENDING') AS status,
                   c.precharge_version AS version,c.charged_at AS preparedAt,
                   c.operator_display_name AS operatorName,c.operator_username AS operatorUsername,
                   c.member_id AS memberId
              FROM wristband_charge_records c JOIN wristbands w ON w.id=c.wristband_id
             WHERE c.precharge_status IS NOT NULL OR
                   (w.status='CHARGED' AND c.member_id IS NULL AND c.id=(SELECT MAX(c2.id) FROM wristband_charge_records c2 WHERE c2.wristband_id=w.id))
             ORDER BY c.id DESC
            """).stream().map(row -> {
                var value = new LinkedHashMap<String,Object>(row);
                value.put("uid", protectedData.decryptField("wristband_charge_records","wristband_uid",row.get("uid")));
                value.put("operatorName", protectedData.decryptField("wristband_charge_records","operator_display_name",row.get("operatorName")));
                value.put("operatorUsername", protectedData.decryptField("wristband_charge_records","operator_username",row.get("operatorUsername")));
                return (Map<String,Object>) value;
            }).filter(row -> uid == null || uid.isBlank() || uid.trim().equals(row.get("uid")))
              .filter(row -> status == null || status.equals(row.get("status"))).toList();
    }

    @Transactional
    public Map<String,Object> prepare(String rawUid, Integer minutes, String requestId,
            OperatorAuthorizationService.AuthorizedOperator operator) {
        String uid = normalizeUid(rawUid);
        validateMinutes(minutes);
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,100}"))
            throw error(HttpStatus.BAD_REQUEST,"INVALID_REQUEST_ID","请求无效，请重新打开预充值窗口");
        var previous = jdbc.queryForList("SELECT id,wristband_uid,duration_minutes FROM wristband_charge_records WHERE precharge_request_id=?",requestId);
        if (!previous.isEmpty()) {
            var saved = previous.get(0);
            if (!uid.equals(protectedData.decryptField("wristband_charge_records","wristband_uid",saved.get("wristband_uid"))) || ((Number)saved.get("duration_minutes")).intValue()!=minutes)
                throw error(HttpStatus.CONFLICT,"PRECHARGE_REQUEST_CONFLICT","预充值请求已变化，请刷新后重试");
            return receipt(((Number)saved.get("id")).longValue());
        }
        String hash = protectedData.wristbandLookupHash(uid), now = clock.instant().toString();
        var bands = jdbc.queryForList("SELECT id,status FROM wristbands WHERE card_uid_lookup_hash=? OR (card_uid_lookup_hash IS NULL AND card_uid=?)",hash,uid);
        if (bands.isEmpty()) {
            jdbc.update("INSERT INTO wristbands(card_uid,card_uid_lookup_hash,status,created_at,updated_at) VALUES (?,?,'IN_STOCK',?,?)",protectedData.encryptField("wristbands","card_uid",uid),hash,now,now);
            bands = jdbc.queryForList("SELECT id,status FROM wristbands WHERE card_uid_lookup_hash=?",hash);
        }
        var band = bands.get(0);
        if ("CHARGED".equals(band.get("status"))) throw error(HttpStatus.CONFLICT,"PRECHARGE_ALREADY_PENDING","此手环已预充值");
        if (!"IN_STOCK".equals(band.get("status"))) throw error(HttpStatus.CONFLICT,"WRISTBAND_ALREADY_BOUND","此手环已发卡，请先还卡");
        long bandId = ((Number)band.get("id")).longValue();
        jdbc.update("UPDATE wristbands SET status='CHARGED',duration_minutes=?,charged_at=?,updated_at=? WHERE id=?",minutes,now,now,bandId);
        jdbc.update("""
            INSERT INTO wristband_charge_records(wristband_id,wristband_uid,duration_minutes,unit_price_cents,amount_cents,
              charged_at,operator_id,operator_username,operator_display_name,precharge_status,precharge_request_id)
            VALUES (?,?,?,0,0,?,?,?,?,'PENDING',?)
            """,bandId,protectedData.encryptField("wristband_charge_records","wristband_uid",uid),minutes,now,
              operator.id(),operator.username(),operator.displayName(),requestId);
        return receipt(jdbc.queryForObject("SELECT last_insert_rowid()",Long.class));
    }

    @Transactional
    public Map<String,Object> change(long id, Integer version, Integer minutes, boolean cancel) {
        if (!cancel) validateMinutes(minutes);
        var rows = jdbc.queryForList("""
            SELECT c.*,w.status AS wristband_status FROM wristband_charge_records c
              JOIN wristbands w ON w.id=c.wristband_id WHERE c.id=?
            """,id);
        if (rows.isEmpty()) throw changed();
        var row = rows.get(0);
        if (version == null || ((Number)row.get("precharge_version")).intValue()!=version || row.get("member_id")!=null
                || !"CHARGED".equals(row.get("wristband_status"))
                || (row.get("precharge_status")!=null && !"PENDING".equals(row.get("precharge_status")))
                || jdbc.queryForObject("SELECT MAX(id) FROM wristband_charge_records WHERE wristband_id=?",Long.class,row.get("wristband_id"))!=id) throw changed();
        String now = clock.instant().toString();
        jdbc.update("UPDATE wristband_charge_records SET precharge_status=?,precharge_version=precharge_version+1,duration_minutes=? WHERE id=?",
                cancel?"CANCELLED":"PENDING",cancel?row.get("duration_minutes"):minutes,id);
        jdbc.update("UPDATE wristbands SET status=?,duration_minutes=?,charged_at=?,updated_at=? WHERE id=?",
                cancel?"IN_STOCK":"CHARGED",cancel?null:minutes,cancel?null:row.get("charged_at"),now,row.get("wristband_id"));
        return receipt(id);
    }

    private Map<String,Object> receipt(long id) {
        return jdbc.queryForMap("SELECT id,duration_minutes AS durationMinutes,precharge_version AS version,COALESCE(precharge_status,'PENDING') AS status FROM wristband_charge_records WHERE id=?",id);
    }

    private static void validateMinutes(Integer minutes) {
        if (minutes == null || minutes<1 || minutes>1440) throw error(HttpStatus.BAD_REQUEST,"INVALID_DURATION","预充值分钟数必须是 1 到 1440 的整数");
    }

    static PlatformApiException changed() {
        return error(HttpStatus.CONFLICT,"PRECHARGE_CHANGED","预充值额度已变化，请刷新或重新刷卡");
    }
}
