package com.ledgame.platform;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LeaderboardService {
    private static final DateTimeFormatter BOUNDARY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ZoneId zoneId;

    public LeaderboardService(JdbcTemplate jdbc, Clock clock, ZoneId zoneId) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.zoneId = zoneId;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getLeaderboard(String rawPeriod) {
        Period period = Period.parse(rawPeriod);
        Instant generatedAt = clock.instant();
        ZonedDateTime localNow = generatedAt.atZone(zoneId);
        ZonedDateTime start = period.start(localNow);
        ZonedDateTime end = period.end(start);

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT m.id AS memberId, m.name AS memberName, m.avatar_id AS avatarId,
                   SUM(g.points_awarded) AS points, COUNT(*) AS completedGames
              FROM members m
              JOIN game_play_records g ON g.member_id=m.id
             WHERE m.status='ACTIVE' AND m.deleted_at IS NULL
               AND g.status='COMPLETED'
               AND g.ended_at IS NOT NULL
               AND g.ended_at>=?
               AND g.ended_at<?
             GROUP BY m.id, m.name, m.avatar_id
             ORDER BY points DESC, m.id ASC
            """, start.toInstant().toString(), end.toInstant().toString());

        List<Map<String, Object>> entries = new ArrayList<>();
        Long previousPoints = null;
        long previousRank = 0;
        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> row = rows.get(index);
            long points = number(row.get("points"));
            long rank = previousPoints != null && previousPoints == points ? previousRank : index + 1L;
            LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
            entry.put("rank", rank);
            entry.put("memberId", number(row.get("memberId")));
            entry.put("memberName", String.valueOf(row.get("memberName")));
            entry.put("avatarId", row.get("avatarId"));
            entry.put("points", points);
            entry.put("completedGames", number(row.get("completedGames")));
            entries.add(entry);
            previousPoints = points;
            previousRank = rank;
        }

        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("period", period.value);
        response.put("periodStart", BOUNDARY_FORMAT.format(start));
        response.put("periodEnd", BOUNDARY_FORMAT.format(end));
        response.put("generatedAt", generatedAt.atZone(zoneId).toOffsetDateTime().toString());
        response.put("entries", entries);
        return response;
    }

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private enum Period {
        DAY("day") {
            @Override ZonedDateTime start(ZonedDateTime now) {
                return now.toLocalDate().atStartOfDay(now.getZone());
            }
            @Override ZonedDateTime end(ZonedDateTime start) { return start.plusDays(1); }
        },
        MONTH("month") {
            @Override ZonedDateTime start(ZonedDateTime now) {
                LocalDate firstDay = now.toLocalDate().withDayOfMonth(1);
                return firstDay.atStartOfDay(now.getZone());
            }
            @Override ZonedDateTime end(ZonedDateTime start) { return start.plusMonths(1); }
        },
        YEAR("year") {
            @Override ZonedDateTime start(ZonedDateTime now) {
                LocalDate firstDay = now.toLocalDate().withDayOfYear(1);
                return firstDay.atStartOfDay(now.getZone());
            }
            @Override ZonedDateTime end(ZonedDateTime start) { return start.plusYears(1); }
        };

        private final String value;
        Period(String value) { this.value = value; }
        abstract ZonedDateTime start(ZonedDateTime now);
        abstract ZonedDateTime end(ZonedDateTime start);

        static Period parse(String raw) {
            String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            for (Period period : values()) if (period.value.equals(value)) return period;
            throw new PlatformApiException(HttpStatus.BAD_REQUEST,
                    "INVALID_LEADERBOARD_PERIOD", "排行榜周期必须是 day、month 或 year");
        }
    }
}
