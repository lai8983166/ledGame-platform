package com.ledgame.platform;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationalDataExportService {
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final int MAX_CSV_CHARACTERS = 32 * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final ProtectedDataService protectedData;

    public OperationalDataExportService(JdbcTemplate jdbc, ProtectedDataService protectedData) {
        this.jdbc = jdbc;
        this.protectedData = protectedData;
    }

    @Transactional(readOnly = true)
    public byte[] members() {
        String sql = """
            WITH scores AS (
              SELECT m.id, m.phone, m.name, m.avatar_id, m.birthday, m.gender,
                     m.status, m.created_at, m.created_by,
                     COALESCE(SUM(CASE WHEN p.status='COMPLETED' THEN p.points_awarded ELSE 0 END), 0) AS points_total
                FROM members m LEFT JOIN game_play_records p ON p.member_id=m.id
               WHERE m.deleted_at IS NULL
               GROUP BY m.id
            )
            SELECT id, phone, name, avatar_id, birthday, gender, status, created_at, created_by,
                   points_total, RANK() OVER (ORDER BY points_total DESC) AS points_rank
              FROM scores ORDER BY id
            """;
        return queryCsv(sql,
                List.of("会员ID", "手机号", "姓名", "头像", "生日", "性别", "状态", "注册时间", "注册来源", "积分", "积分排名"),
                row -> {
            decrypt(row, "phone", "members", "phone");
            decrypt(row, "name", "members", "name");
            decrypt(row, "avatar_id", "members", "avatar_id");
            decrypt(row, "birthday", "members", "birthday");
            decrypt(row, "gender", "members", "gender");
        }, "id", "phone", "name", "avatar_id", "birthday", "gender", "status", "created_at", "created_by", "points_total", "points_rank");
    }

    @Transactional(readOnly = true)
    public byte[] wristbandCharges() {
        return queryCsv("""
            SELECT id, wristband_uid, duration_minutes, unit_price_cents, amount_cents,
                   issued_at, charged_at, operator_id, operator_username, operator_display_name
              FROM wristband_charge_records ORDER BY id
            """, List.of("交易ID", "手环UID", "充值分钟", "每分钟单价（分）", "交易金额（分）", "发卡时间", "充值时间", "操作员ID", "操作员账号", "操作员名称"),
                row -> {
                    decrypt(row, "wristband_uid", "wristband_charge_records", "wristband_uid");
                    decrypt(row, "operator_username", "wristband_charge_records", "operator_username");
                    decrypt(row, "operator_display_name", "wristband_charge_records", "operator_display_name");
                },
                "id", "wristband_uid", "duration_minutes", "unit_price_cents", "amount_cents", "issued_at", "charged_at", "operator_id", "operator_username", "operator_display_name");
    }

    @Transactional(readOnly = true)
    public byte[] gamePlays() {
        return queryCsv("""
            SELECT p.id, p.external_session_id, p.participant_index, p.member_id, m.phone, m.name,
                   p.wristband_uid, p.device_id, p.room_id, p.game_id, p.game_name, p.status,
                   p.started_at, p.ended_at, p.success, p.termination_reason, p.raw_score,
                   p.points_awarded, p.scoring_policy
             FROM game_play_records p LEFT JOIN members m ON m.id=p.member_id
             ORDER BY p.id
            """, List.of("游玩ID", "场次ID", "玩家序号", "会员ID", "手机号", "会员姓名", "手环UID", "设备ID", "房间ID", "游戏ID", "游戏名称", "状态", "开始时间", "结束时间", "是否成功", "结束原因", "原始分数", "获得积分", "积分策略"),
                row -> {
            decrypt(row, "phone", "members", "phone");
            decrypt(row, "name", "members", "name");
            decrypt(row, "wristband_uid", "game_play_records", "wristband_uid");
        }, "id", "external_session_id", "participant_index", "member_id", "phone", "name", "wristband_uid", "device_id", "room_id", "game_id", "game_name", "status", "started_at", "ended_at", "success", "termination_reason", "raw_score", "points_awarded", "scoring_policy");
    }

    private byte[] queryCsv(String sql, List<String> headers, Consumer<Map<String, Object>> transform,
            String... fields) {
        StringBuilder output = new StringBuilder();
        appendRow(output, headers);
        jdbc.query(sql, resultSet -> {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String field : fields) row.put(field, resultSet.getObject(field));
            transform.accept(row);
            List<String> values = new ArrayList<>(fields.length);
            Arrays.stream(fields).forEach(field -> values.add(stringValue(row.get(field))));
            appendRow(output, values);
            requireWithinLimit(output);
        });
        return withBom(output);
    }

    byte[] csv(List<String> headers, List<Map<String, Object>> rows, String... fields) {
        StringBuilder output = new StringBuilder();
        appendRow(output, headers);
        for (Map<String, Object> row : rows) {
            List<String> values = new ArrayList<>(fields.length);
            Arrays.stream(fields).forEach(field -> values.add(stringValue(row.get(field))));
            appendRow(output, values);
        }
        requireWithinLimit(output);
        return withBom(output);
    }

    private static byte[] withBom(StringBuilder output) {
        byte[] body = output.toString().getBytes(StandardCharsets.UTF_8);
        byte[] result = Arrays.copyOf(UTF8_BOM, UTF8_BOM.length + body.length);
        System.arraycopy(body, 0, result, UTF8_BOM.length, body.length);
        return result;
    }

    private static void requireWithinLimit(StringBuilder output) {
        if (output.length() > MAX_CSV_CHARACTERS) {
            throw new PlatformApiException(HttpStatus.PAYLOAD_TOO_LARGE, "EXPORT_TOO_LARGE",
                    "导出数据超过单次上限，请联系厂家使用专用迁移工具");
        }
    }

    private void decrypt(Map<String, Object> row, String field, String table, String column) {
        if (row.get(field) != null) {
            row.put(field, protectedData.decryptField(table, column, row.get(field)));
        }
    }

    private static String stringValue(Object value) {
        if (value == null) return "";
        if (value instanceof Boolean bool) return bool ? "是" : "否";
        return String.valueOf(value);
    }

    private static void appendRow(StringBuilder output, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) output.append(',');
            String value = values.get(index);
            if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                output.append('"').append(value.replace("\"", "\"\"")).append('"');
            } else output.append(value);
        }
        output.append("\r\n");
    }
}
