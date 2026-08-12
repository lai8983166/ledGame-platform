package com.ledgame.platform;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GamePlayService {
    private static final String PLAY_VIEW_SQL = """
        SELECT id, member_id AS memberId, binding_id AS bindingId, wristband_uid AS uid,
               device_id AS deviceId, room_id AS roomId, external_session_id AS externalSessionId,
               game_id AS gameId, game_name AS gameName, status, started_at AS startedAt,
               ended_at AS endedAt, success, termination_reason AS terminationReason,
               raw_score AS rawScore, points_awarded AS pointsAwarded, result_json AS resultJson
          FROM game_play_records
        """;

    private final JdbcTemplate jdbc;
    private final GameAccessService accessService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public GamePlayService(
            JdbcTemplate jdbc,
            GameAccessService accessService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.jdbc = jdbc;
        this.accessService = accessService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
            SELECT g.id, g.member_id AS memberId, m.name AS memberName,
                   g.binding_id AS bindingId, g.wristband_uid AS uid,
                   g.device_id AS deviceId, g.room_id AS roomId,
                   g.external_session_id AS externalSessionId,
                   g.game_id AS gameId, g.game_name AS gameName, g.status,
                   g.started_at AS startedAt, g.ended_at AS endedAt,
                   g.success, g.termination_reason AS terminationReason,
                   g.raw_score AS rawScore, g.points_awarded AS pointsAwarded,
                   g.result_json AS resultJson
              FROM game_play_records g
              JOIN members m ON m.id=g.member_id
             ORDER BY g.started_at DESC, g.id DESC
             LIMIT 200
            """).stream().map(this::playView).toList();
    }

    @Transactional
    public Map<String, Object> start(StartCommand command) {
        requireText(command.deviceId(), "deviceId");
        requireText(command.externalSessionId(), "externalSessionId");
        requireText(command.gameId(), "gameId");
        requireText(command.gameName(), "gameName");

        List<Map<String, Object>> existing = jdbc.queryForList(
            PLAY_VIEW_SQL + " WHERE device_id=? AND external_session_id=?",
            command.deviceId().trim(), command.externalSessionId().trim());
        if (!existing.isEmpty()) return playView(existing.get(0));

        Map<String, Object> access = accessService.requireActiveAccess(command.uid());
        long bindingId = number(access.get("bindingId"));
        List<Map<String, Object>> running = jdbc.queryForList(
            PLAY_VIEW_SQL + " WHERE binding_id=? AND status='RUNNING'", bindingId);
        if (!running.isEmpty()) {
            throw GameAccessService.error(
                HttpStatus.CONFLICT, "WRISTBAND_IN_USE", "该手环已有正在进行的游戏");
        }

        try {
            jdbc.update("""
                INSERT INTO game_play_records(
                    member_id, binding_id, wristband_uid, device_id, room_id,
                    external_session_id, game_id, game_name, status, started_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?)
                """,
                number(access.get("memberId")), bindingId, access.get("uid"),
                command.deviceId().trim(), blankToNull(command.roomId()),
                command.externalSessionId().trim(), command.gameId().trim(),
                command.gameName().trim(), clock.instant().toString());
        } catch (DataIntegrityViolationException exception) {
            List<Map<String, Object>> idempotent = jdbc.queryForList(
                PLAY_VIEW_SQL + " WHERE device_id=? AND external_session_id=?",
                command.deviceId().trim(), command.externalSessionId().trim());
            if (!idempotent.isEmpty()) return playView(idempotent.get(0));
            throw GameAccessService.error(
                HttpStatus.CONFLICT, "WRISTBAND_IN_USE", "该手环已有正在进行的游戏");
        }
        return playView(jdbc.queryForMap(
            PLAY_VIEW_SQL + " WHERE device_id=? AND external_session_id=?",
            command.deviceId().trim(), command.externalSessionId().trim()));
    }

    @Transactional
    public Map<String, Object> settle(long playId, ResultCommand command) {
        Map<String, Object> existing = find(playId);
        if (!"RUNNING".equals(String.valueOf(existing.get("status")))) {
            return playView(existing);
        }
        String reason = requireText(command.terminationReason(), "terminationReason");
        boolean completed = reason.startsWith("NATURAL_");
        String status = completed ? "COMPLETED" : "ABORTED";
        int awarded = completed ? Math.max(0, command.pointsAwarded() == null ? 0 : command.pointsAwarded()) : 0;
        Integer success = command.success() == null ? null : (command.success() ? 1 : 0);
        int updated = jdbc.update("""
            UPDATE game_play_records
               SET status=?, ended_at=?, success=?, termination_reason=?, raw_score=?,
                   points_awarded=?, result_json=?
             WHERE id=? AND status='RUNNING'
            """, status, clock.instant().toString(), success, reason, command.rawScore(), awarded,
            toJson(command.resultPayload()), playId);
        if (updated == 0) return playView(find(playId));
        return playView(find(playId));
    }

    private Map<String, Object> find(long playId) {
        List<Map<String, Object>> rows = jdbc.queryForList(PLAY_VIEW_SQL + " WHERE id=?", playId);
        if (rows.isEmpty()) {
            throw GameAccessService.error(HttpStatus.NOT_FOUND, "GAME_PLAY_NOT_FOUND", "未找到该局游戏记录");
        }
        return rows.get(0);
    }

    private Map<String, Object> playView(Map<String, Object> row) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>(row);
        Object success = row.get("success");
        result.put("success", success == null ? null : number(success) != 0);
        Object json = row.get("resultJson");
        if (json != null) {
            try {
                result.put("resultPayload", objectMapper.readValue(String.valueOf(json), Object.class));
            } catch (JsonProcessingException exception) {
                result.put("resultPayload", null);
            }
        } else {
            result.put("resultPayload", null);
        }
        result.remove("resultJson");
        return result;
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw GameAccessService.error(HttpStatus.BAD_REQUEST, "INVALID_RESULT_PAYLOAD", "游戏结果数据无法保存");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw GameAccessService.error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "缺少字段 " + field);
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    public record StartCommand(
        String uid,
        String deviceId,
        String roomId,
        String externalSessionId,
        String gameId,
        String gameName
    ) {}

    public record ResultCommand(
        Boolean success,
        String terminationReason,
        Integer rawScore,
        Integer pointsAwarded,
        Object resultPayload
    ) {}
}
