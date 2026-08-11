package com.ledgame.platform;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RoomSettingsService {
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");
    private final JdbcTemplate jdbc;

    public RoomSettingsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> merge(List<Map<String, Object>> projections) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> projection : projections) {
            String ip = RoomConnectionRegistry.normalizeIp(String.valueOf(projection.get("ip")));
            merged.put(ip, withName(projection, ip));
        }
        for (Map<String, Object> setting : jdbc.queryForList(
                "SELECT room_ip AS ip, display_name AS roomName FROM room_settings ORDER BY room_ip")) {
            String ip = RoomConnectionRegistry.normalizeIp(String.valueOf(setting.get("ip")));
            if (!merged.containsKey(ip)) merged.put(ip, offlineProjection(ip, String.valueOf(setting.get("roomName"))));
        }
        return new ArrayList<>(merged.values());
    }

    public Map<String, Object> saveName(String rawIp, String rawName) {
        String ip = validateIp(rawIp);
        String name = rawName == null ? "" : rawName.trim();
        if (name.isBlank() || name.length() > 40) {
            throw new PlatformApiException(HttpStatus.BAD_REQUEST, "ROOM_NAME_INVALID",
                    "Room name must contain 1 to 40 characters");
        }
        String now = Instant.now().toString();
        jdbc.update("""
                INSERT INTO room_settings(room_ip, display_name, created_at, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(room_ip) DO UPDATE SET display_name=excluded.display_name, updated_at=excluded.updated_at
                """, ip, name, now, now);
        return Map.of("ip", ip, "roomName", name, "updatedAt", now);
    }

    public String validateIp(String rawIp) {
        String ip = RoomConnectionRegistry.normalizeIp(rawIp);
        if (ip.isBlank() || ip.length() > 45 || (!IPV4.matcher(ip).matches() && !ip.contains(":"))) {
            throw new PlatformApiException(HttpStatus.BAD_REQUEST, "ROOM_IP_INVALID", "Invalid room IP");
        }
        if (IPV4.matcher(ip).matches()) {
            for (String part : ip.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    throw new PlatformApiException(HttpStatus.BAD_REQUEST, "ROOM_IP_INVALID", "Invalid room IP");
                }
            }
        }
        return ip;
    }

    private Map<String, Object> withName(Map<String, Object> source, String ip) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        String current = String.valueOf(result.getOrDefault("roomName", "")).trim();
        Map<String, Object> setting = findSetting(ip);
        result.put("roomName", setting == null ? (current.isBlank() ? ip : current) : setting.get("roomName"));
        return result;
    }

    private Map<String, Object> findSetting(String ip) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT display_name AS roomName FROM room_settings WHERE room_ip = ?", ip);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Map<String, Object> offlineProjection(String ip, String name) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ip", ip);
        result.put("deviceId", "");
        result.put("roomId", "");
        result.put("roomName", name);
        result.put("connectionId", "");
        result.put("online", false);
        result.put("state", Map.of());
        result.put("lastSequence", -1L);
        result.put("lastEventType", null);
        result.put("lastEventAt", null);
        result.put("queueLength", 0);
        return result;
    }
}
