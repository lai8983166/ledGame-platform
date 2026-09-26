package com.ledgame.platform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LegacyGameCompatibilityService {
    private static final Logger log = LoggerFactory.getLogger(LegacyGameCompatibilityService.class);
    private final JdbcTemplate jdbc;
    private final GameAccessService access;
    private final GamePlayService plays;
    private final LegacyRoomPresenceService rooms;
    private final ProtectedDataService protectedData;
    private final ObjectMapper mapper;
    private final Clock clock;

    public LegacyGameCompatibilityService(JdbcTemplate jdbc, GameAccessService access, GamePlayService plays,
            LegacyRoomPresenceService rooms, ProtectedDataService protectedData, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.plays = plays;
        this.rooms = rooms;
        this.protectedData = protectedData;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public LegacyResponse handle(JsonNode request, String sourceIp) {
        String ip = RoomConnectionRegistry.normalizeIp(sourceIp);
        int command = request == null ? -1 : request.path("cmd").asInt(-1);
        if (command == 1) {
            rooms.heartbeat(ip);
            return LegacyResponse.success(null);
        }
        if (command == 5) return admission(request, ip);
        if (command == 2) return start(request, ip);
        if (command == 3) return settle(request, ip);
        log.warn("legacy_callback_rejected cmd={} roomIp={} correlationId={} code=LEGACY_COMMAND_UNSUPPORTED",
                command, ip, UUID.randomUUID());
        return LegacyResponse.failure(400, "Unsupported legacy command");
    }

    private LegacyResponse admission(JsonNode request, String ip) {
        JsonNode payload = requiredObject(request, "json");
        List<String> uids = uidList(payload.get("icList"), "icList");
        List<Map<String, Object>> admitted = access.activateLegacyBatch(uids);
        List<Object> tokens = new ArrayList<>();
        for (Map<String, Object> item : admitted) {
            @SuppressWarnings("unchecked") Map<String, Object> accessInfo = (Map<String, Object>) item.get("access");
            LinkedHashMap<String, Object> token = new LinkedHashMap<>();
            token.put("ic", accessInfo.get("uid"));
            token.put("type", 2);
            // Fastjson serializes java.util.Date as epoch milliseconds by default; return that legacy wire form.
            token.put("endTime", Instant.parse(String.valueOf(accessInfo.get("expiresAt"))).toEpochMilli());
            token.put("durationMinutes", accessInfo.get("durationMinutes"));
            tokens.add(Arrays.asList(null, token));
        }
        rooms.heartbeat(ip);
        return LegacyResponse.success(tokens);
    }

    private LegacyResponse start(JsonNode request, String ip) {
        JsonNode payload = requiredObject(request, "json");
        String gameId = requiredText(request, "gameId");
        String gameName = requiredText(request, "gameName");
        List<String> uids = participantList(payload.get("icList"), "icList");
        boolean isAdmin = optionalBoolean(payload, "isAdmin");
        rooms.heartbeat(ip);
        if (uids.isEmpty() || uids.stream().allMatch(LegacyGameCompatibilityService::isMockUid)) {
            // The old touch-only path may report mock identifiers. Keep the room visible but never create fake members.
            return LegacyResponse.success(Map.of("tracked", false));
        }
        if (uids.stream().anyMatch(LegacyGameCompatibilityService::isMockUid)) {
            throw invalid("A legacy session cannot mix mock and member wristbands");
        }
        if (new LinkedHashSet<>(uids).size() != uids.size()) throw invalid("Duplicate participants");

        String participantsHash = hash(uids);
        List<Map<String, Object>> open = jdbc.queryForList("""
                SELECT session_id AS sessionId, game_id AS gameId, game_name AS gameName,
                       participants_hash AS participantsHash
                  FROM legacy_game_sessions WHERE room_ip=? AND status='RUNNING'
                 LIMIT 1
                """, ip);
        if (!open.isEmpty()) {
            Map<String, Object> current = open.get(0);
            if (gameId.equals(String.valueOf(current.get("gameId")))
                    && gameName.equals(String.valueOf(current.get("gameName")))
                    && participantsHash.equals(String.valueOf(current.get("participantsHash")))) {
                return LegacyResponse.success(Map.of("sessionId", current.get("sessionId"), "duplicate", true));
            }
            throw GameAccessService.error(HttpStatus.CONFLICT, "LEGACY_ROOM_SESSION_CONFLICT",
                    "This room already has a different legacy game session running");
        }

        String sessionId = UUID.randomUUID().toString();
        plays.startBatch(new GamePlayService.BatchStartCommand(uids, ip, ip, sessionId, gameId, gameName));
        jdbc.update("""
                INSERT INTO legacy_game_sessions(
                    session_id, room_ip, game_id, game_name, participants_hash, is_admin,
                    status, started_at)
                VALUES (?, ?, ?, ?, ?, ?, 'RUNNING', ?)
                """, sessionId, ip, gameId, gameName, participantsHash, isAdmin ? 1 : 0,
                clock.instant().toString());
        return LegacyResponse.success(Map.of("sessionId", sessionId));
    }

    private LegacyResponse settle(JsonNode request, String ip) {
        JsonNode payload = requiredObject(request, "json");
        String gameId = requiredText(request, "gameId");
        List<String> listedUids = participantList(payload.get("icList"), "icList");
        Map<String, Long> reported = reportedPoints(payload.get("points"));
        boolean isAdmin = optionalBoolean(payload, "isAdmin");
        if (new LinkedHashSet<>(listedUids).size() != listedUids.size()) throw invalid("Duplicate participants");
        if (!new LinkedHashSet<>(listedUids).equals(reported.keySet())) {
            throw GameAccessService.error(HttpStatus.CONFLICT, "LEGACY_POINTS_PARTICIPANTS_MISMATCH",
                    "Legacy point entries do not match the reported participants");
        }
        if (listedUids.isEmpty() || listedUids.stream().allMatch(LegacyGameCompatibilityService::isMockUid)) {
            return LegacyResponse.success(Map.of("tracked", false));
        }
        if (listedUids.stream().anyMatch(LegacyGameCompatibilityService::isMockUid)) {
            throw GameAccessService.error(HttpStatus.CONFLICT, "LEGACY_POINTS_PARTICIPANTS_MISMATCH",
                    "Legacy points cannot mix mock and member participants");
        }
        String fingerprint = settlementFingerprint(gameId, reported, isAdmin);
        Integer duplicate = jdbc.queryForObject("""
                SELECT COUNT(*) FROM legacy_game_sessions
                 WHERE room_ip=? AND game_id=? AND status='SETTLED' AND settlement_fingerprint=?
                """, Integer.class, ip, gameId, fingerprint);
        if (duplicate != null && duplicate > 0) return LegacyResponse.success(Map.of("duplicate", true));

        List<Map<String, Object>> open = jdbc.queryForList("""
                SELECT session_id AS sessionId, is_admin AS isAdmin FROM legacy_game_sessions
                 WHERE room_ip=? AND game_id=? AND status='RUNNING' LIMIT 1
                """, ip, gameId);
        if (open.isEmpty()) {
            throw GameAccessService.error(HttpStatus.CONFLICT, "LEGACY_SESSION_NOT_FOUND",
                    "No open legacy session matches this room and game");
        }
        String sessionId = String.valueOf(open.get(0).get("sessionId"));
        List<Map<String, Object>> participants = jdbc.queryForList("""
                SELECT p.id, p.wristband_uid AS uid
                  FROM game_play_records p
                 WHERE p.external_session_id=? AND p.status='RUNNING'
                 ORDER BY p.participant_index, p.id
                """, sessionId);
        LinkedHashMap<Long, Long> pointsByPlayId = new LinkedHashMap<>();
        for (Map<String, Object> participant : participants) {
            String uid = protectedData.decryptField("game_play_records", "wristband_uid", participant.get("uid"));
            Long points = reported.get(uid);
            if (points == null) {
                throw GameAccessService.error(HttpStatus.CONFLICT, "LEGACY_POINTS_PARTICIPANTS_MISMATCH",
                        "Legacy point entries do not match the open session participants");
            }
            pointsByPlayId.put(((Number) participant.get("id")).longValue(), points);
        }
        if (participants.isEmpty() || pointsByPlayId.size() != reported.size()) {
            throw GameAccessService.error(HttpStatus.CONFLICT, "LEGACY_POINTS_PARTICIPANTS_MISMATCH",
                    "Legacy point entries do not match the open session participants");
        }
        for (Map<String, Object> participant : participants) {
            long playId = ((Number) participant.get("id")).longValue();
            long points = pointsByPlayId.get(playId);
            LinkedHashMap<String, Object> stored = new LinkedHashMap<>();
            stored.put("legacyGameId", gameId);
            stored.put("legacyRoomIp", ip);
            stored.put("startIsAdmin", ((Number) open.get(0).get("isAdmin")).intValue() != 0);
            stored.put("endIsAdmin", isAdmin);
            stored.put("legacyTerminationReason", "unknown");
            stored.put("reportedPoints", points);
            plays.settleLegacy(playId, points, stored);
        }
        int changed = jdbc.update("""
                UPDATE legacy_game_sessions SET status='SETTLED', ended_at=?, settlement_fingerprint=?
                 WHERE session_id=? AND status='RUNNING'
                """, clock.instant().toString(), fingerprint, sessionId);
        if (changed != 1) {
            throw GameAccessService.error(HttpStatus.CONFLICT, "LEGACY_SESSION_STATE_CHANGED",
                    "Legacy session changed while its result was being settled");
        }
        rooms.heartbeat(ip);
        return LegacyResponse.success(Map.of("sessionId", sessionId, "settledParticipants", participants.size()));
    }

    private List<String> uidList(JsonNode value, String field) {
        if (value == null || !value.isArray()) throw invalid("Expected an array for " + field);
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) throw invalid("Invalid wristband entry");
            result.add(GameAccessService.normalizeUid(item.asText()));
        }
        return result;
    }

    private List<String> participantList(JsonNode value, String field) {
        if (value == null || !value.isArray()) throw invalid("Expected an array for " + field);
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) throw invalid("Invalid participant entry");
            String rawUid = item.asText().trim();
            result.add(isMockUid(rawUid) ? rawUid : GameAccessService.normalizeUid(rawUid));
        }
        return result;
    }

    private Map<String, Long> reportedPoints(JsonNode value) {
        if (value == null || !value.isArray()) throw invalid("Expected point entries");
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        for (JsonNode item : value) {
            if (!item.isObject() || !item.path("points").isIntegralNumber() || !item.path("points").canConvertToLong()) {
                throw GameAccessService.error(HttpStatus.BAD_REQUEST, "LEGACY_POINTS_INVALID",
                        "Legacy points must be a non-negative 64-bit integer");
            }
            String rawUid = item.path("ic").asText(null);
            String uid = isMockUid(rawUid) ? rawUid : GameAccessService.normalizeUid(rawUid);
            long points = item.path("points").longValue();
            if (points < 0) throw GameAccessService.error(HttpStatus.BAD_REQUEST, "LEGACY_POINTS_INVALID",
                    "Legacy points must be a non-negative 64-bit integer");
            if (result.putIfAbsent(uid, points) != null) throw invalid("Duplicate point entry");
        }
        return result;
    }

    private boolean optionalBoolean(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) return false;
        if (!value.isBoolean()) throw invalid("Expected a Boolean for " + field);
        return value.booleanValue();
    }

    private JsonNode requiredObject(JsonNode request, String field) {
        JsonNode value = request == null ? null : request.get(field);
        if (value == null || !value.isObject()) throw invalid("Expected an object for " + field);
        return value;
    }

    private String requiredText(JsonNode request, String field) {
        JsonNode value = request == null ? null : request.get(field);
        if (value == null || !(value.isTextual() || value.isIntegralNumber())) throw invalid("Missing " + field);
        String text = value.asText().trim();
        if (text.isBlank() || text.length() > 120) throw invalid("Invalid " + field);
        return text;
    }

    private String hash(Object value) {
        try {
            byte[] canonical = mapper.writeValueAsBytes(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("LEGACY_FINGERPRINT_FAILED", exception);
        }
    }

    private String settlementFingerprint(String gameId, Map<String, Long> reported, boolean isAdmin) {
        List<Map<String, Object>> canonicalPoints = reported.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Map.<String, Object>of("ic", entry.getKey(), "points", entry.getValue()))
                .toList();
        return hash(List.of(gameId, isAdmin, canonicalPoints));
    }

    private static boolean isMockUid(String value) {
        return value != null && value.matches("(?i)mock[_-][a-z0-9_-]{1,60}");
    }

    private static PlatformApiException invalid(String message) {
        return GameAccessService.error(HttpStatus.BAD_REQUEST, "LEGACY_REQUEST_INVALID", message);
    }

    public record LegacyResponse(int code, String msg, Object data) {
        static LegacyResponse success(Object data) { return new LegacyResponse(200, "success", data); }
        static LegacyResponse failure(int code, String message) {
            return new LegacyResponse(code, message == null || message.isBlank() ? "request rejected" : message, null);
        }
    }
}
