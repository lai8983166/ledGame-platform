package com.ledgame.platform;

import static com.ledgame.platform.GameAccessService.error;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberPointsService {
    private static final String RECORDS="""
        SELECT id,member_id AS memberId,points_deducted AS points,before_points AS beforePoints,
               after_points AS afterPoints,ranking_points AS rankingPoints,reason,deducted_at AS deductedAt,
               operator_id AS operatorId,operator_username AS operatorUsername,operator_display_name AS operatorLabel
          FROM member_point_deductions
        """;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MemberPointsService(JdbcTemplate jdbc,Clock clock) { this.jdbc=jdbc; this.clock=clock; }

    @Transactional(readOnly=true)
    public Map<String,Long> balance(long memberId) {
        long ranking=jdbc.queryForObject("SELECT COALESCE(SUM(points_awarded),0) FROM game_play_records WHERE member_id=? AND status IN ('COMPLETED','LEGACY_SETTLED')",Long.class,memberId);
        long spent=jdbc.queryForObject("SELECT COALESCE(SUM(points_deducted),0) FROM member_point_deductions WHERE member_id=?",Long.class,memberId);
        return Map.of("ranking",ranking,"remaining",ranking-spent);
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> history(long memberId) {
        return jdbc.queryForList(RECORDS+" WHERE member_id=? ORDER BY id DESC LIMIT 100",memberId);
    }

    @Transactional
    public Map<String,Object> deduct(long memberId,BigDecimal rawPoints,String rawReason,String requestId,
            OperatorAuthorizationService.AuthorizedOperator operator) {
        if (rawPoints==null || rawPoints.stripTrailingZeros().scale()>0 || rawPoints.signum()<=0 || rawPoints.compareTo(BigDecimal.valueOf(9007199254740991L))>0)
            throw error(HttpStatus.BAD_REQUEST,"INVALID_POINTS","扣减积分必须是有效的正整数");
        long points=rawPoints.longValueExact();
        String reason=rawReason==null?"":rawReason.strip();
        if (reason.isBlank() || reason.length()>500) throw error(HttpStatus.BAD_REQUEST,"INVALID_POINTS_REASON","请填写扣减原因（最多 500 字）");
        if (requestId==null || !requestId.matches("[A-Za-z0-9_-]{1,100}")) throw error(HttpStatus.BAD_REQUEST,"INVALID_REQUEST_ID","请求无效，请重新打开扣减窗口");
        var previous=jdbc.queryForList("SELECT id,member_id,points_deducted,reason FROM member_point_deductions WHERE request_id=?",requestId);
        if (!previous.isEmpty()) {
            var saved=previous.get(0);
            if (((Number)saved.get("member_id")).longValue()!=memberId || ((Number)saved.get("points_deducted")).longValue()!=points || !reason.equals(saved.get("reason")))
                throw error(HttpStatus.CONFLICT,"POINT_DEDUCTION_REQUEST_CONFLICT","扣减请求内容已变化，请重新办理");
            return jdbc.queryForMap(RECORDS+" WHERE id=?",saved.get("id"));
        }
        if (jdbc.queryForList("SELECT id FROM members WHERE id=? AND status='ACTIVE' AND deleted_at IS NULL",memberId).isEmpty())
            throw error(HttpStatus.NOT_FOUND,"MEMBER_NOT_FOUND","会员不存在或已停用");
        var balance=balance(memberId);
        long before=balance.get("remaining");
        if (points>before) throw error(HttpStatus.CONFLICT,"MEMBER_INSUFFICIENT_POINTS","剩余积分不足，不能扣减");
        jdbc.update("""
            INSERT INTO member_point_deductions(member_id,request_id,points_deducted,before_points,after_points,
              ranking_points,reason,deducted_at,operator_id,operator_username,operator_display_name) VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """,memberId,requestId,points,before,before-points,balance.get("ranking"),reason,clock.instant().toString(),operator.id(),operator.username(),operator.displayName());
        return jdbc.queryForMap(RECORDS+" WHERE id=?",jdbc.queryForObject("SELECT last_insert_rowid()",Long.class));
    }
}
