package com.ledgame.platform;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardOverviewService {
    private static final DateTimeFormatter BOUNDARY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ZoneId zoneId;

    public DashboardOverviewService(JdbcTemplate jdbc, Clock clock, ZoneId zoneId) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.zoneId = zoneId;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        Instant generatedAt = clock.instant();
        ZonedDateTime start = generatedAt.atZone(zoneId).toLocalDate().atStartOfDay(zoneId);
        ZonedDateTime end = start.plusDays(1);
        String startInstant = start.toInstant().toString();
        String endInstant = end.toInstant().toString();

        int totalMembers = count("""
            SELECT COUNT(*) FROM members
             WHERE status='ACTIVE' AND deleted_at IS NULL
            """);
        int newMembersToday = count("""
            SELECT COUNT(*) FROM members
             WHERE status='ACTIVE' AND deleted_at IS NULL
               AND julianday(created_at)>=julianday(?)
               AND julianday(created_at)<julianday(?)
            """, startInstant, endInstant);
        Map<String, Object> charges = jdbc.queryForMap("""
            SELECT COUNT(*) AS chargeCount, COALESCE(SUM(amount_cents), 0) AS revenueCents
              FROM wristband_charge_records
             WHERE julianday(charged_at)>=julianday(?)
               AND julianday(charged_at)<julianday(?)
            """, startInstant, endInstant);

        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("totalMembers", totalMembers);
        result.put("newMembersToday", newMembersToday);
        result.put("wristbandsChargedToday", number(charges.get("chargeCount")));
        result.put("revenueTodayCents", number(charges.get("revenueCents")));
        result.put("periodStart", BOUNDARY_FORMAT.format(start));
        result.put("periodEnd", BOUNDARY_FORMAT.format(end));
        result.put("generatedAt", generatedAt.atZone(zoneId).toOffsetDateTime().toString());
        return result;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static long number(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }
}
