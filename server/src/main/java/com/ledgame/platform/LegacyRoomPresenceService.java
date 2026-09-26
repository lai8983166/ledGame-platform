package com.ledgame.platform;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Ephemeral presence for the old game client, whose only identity is its socket IP. */
@Service
public class LegacyRoomPresenceService {
    private static final Duration ONLINE_WINDOW = Duration.ofSeconds(60);
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ProtectedDataService protectedData;
    private final ConcurrentMap<String, Instant> lastSeenByIp = new ConcurrentHashMap<>();
    private final Set<String> registeredThisProcess = ConcurrentHashMap.newKeySet();

    public LegacyRoomPresenceService(JdbcTemplate jdbc, Clock clock, ProtectedDataService protectedData) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.protectedData = protectedData;
    }

    public String heartbeat(String rawIp) {
        String ip = RoomConnectionRegistry.normalizeIp(rawIp);
        if (registeredThisProcess.add(ip)) {
            String now = clock.instant().toString();
            jdbc.update("""
                    INSERT INTO room_settings(room_ip, display_name, created_at, updated_at)
                    VALUES (?, ?, ?, ?) ON CONFLICT(room_ip) DO NOTHING
                    """, ip, ip, now, now);
        }
        lastSeenByIp.put(ip, clock.instant());
        return ip;
    }

    public List<Map<String, Object>> list() {
        Map<String, Map<String, Object>> rooms = new LinkedHashMap<>();
        for (String ip : jdbc.queryForList(
                "SELECT DISTINCT room_ip FROM legacy_game_sessions ORDER BY room_ip", String.class)) {
            rooms.put(ip, projection(ip));
        }
        for (String ip : lastSeenByIp.keySet()) rooms.put(ip, projection(ip));
        return rooms.values().stream()
                .sorted(Comparator.comparing(room -> String.valueOf(room.get("ip"))))
                .toList();
    }

    public Map<String, Object> find(String rawIp) {
        String ip = RoomConnectionRegistry.normalizeIp(rawIp);
        boolean known = lastSeenByIp.containsKey(ip)
                || !jdbc.queryForList("SELECT room_ip FROM legacy_game_sessions WHERE room_ip=? LIMIT 1", ip).isEmpty();
        return known ? projection(ip) : null;
    }

    private Map<String, Object> projection(String ip) {
        Instant lastSeen = lastSeenByIp.get(ip);
        Instant now = clock.instant();
        boolean online = lastSeen != null && !lastSeen.isBefore(now.minus(ONLINE_WINDOW));
        List<Map<String, Object>> sessions = jdbc.queryForList("""
                SELECT session_id AS sessionId, game_id AS gameId, game_name AS gameName, started_at AS startedAt
                  FROM legacy_game_sessions WHERE room_ip=? AND status='RUNNING'
                 ORDER BY started_at DESC LIMIT 1
                """, ip);
        Map<String, Object> active = sessions.isEmpty() ? null : sessions.get(0);
        List<Map<String, Object>> players = active == null ? List.of() : players(String.valueOf(active.get("sessionId")));

        LinkedHashMap<String, Object> state = new LinkedHashMap<>();
        state.put("engineState", active == null ? "IDLE" : "RUNNING");
        if (active != null) state.put("gameName", active.get("gameName"));
        state.put("legacyCompatibility", true);
        state.put("hardware", List.of(Map.of(
                "id", "elc408-controller", "name", "ELC-408 controller",
                "location", "Game terminal", "status", "unknown",
                "detail", "Legacy protocol does not report controller health")));

        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("ip", ip);
        result.put("deviceId", ip);
        result.put("roomId", ip);
        result.put("roomName", ip);
        result.put("connectionId", "legacy:" + ip);
        result.put("online", online);
        result.put("state", state);
        result.put("lastSequence", -1L);
        result.put("lastEventType", active == null ? "LEGACY_HEARTBEAT" : "LEGACY_GAME_STARTED");
        result.put("lastEventAt", lastSeen == null ? null : lastSeen.toString());
        result.put("queueLength", 0);
        result.put("players", players);
        return result;
    }

    private List<Map<String, Object>> players(String sessionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.id, p.member_id AS memberId, p.participant_index AS participantIndex,
                       m.name AS memberName
                  FROM game_play_records p JOIN members m ON m.id=p.member_id
                 WHERE p.external_session_id=? AND p.status='RUNNING'
                 ORDER BY p.participant_index, p.id
                """, sessionId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String name = protectedData.decryptField("members", "name", row.get("memberName"));
            LinkedHashMap<String, Object> player = new LinkedHashMap<>();
            player.put("id", row.get("memberId"));
            player.put("memberId", row.get("memberId"));
            player.put("name", name);
            player.put("playerIndex", row.get("participantIndex"));
            // Legacy callbacks carry no live score, so do not fabricate one.
            result.add(player);
        }
        return result;
    }

}
