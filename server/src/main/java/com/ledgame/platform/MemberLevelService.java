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
public class MemberLevelService {
    private static final String PROJECTION = "SELECT id,name,min_points AS minPoints,max_points AS maxPoints FROM member_levels";
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MemberLevelService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> list() {
        return jdbc.queryForList(PROJECTION + " ORDER BY min_points,id");
    }

    @Transactional
    public Map<String,Object> save(Long id, String rawName, BigDecimal rawMin, BigDecimal rawMax) {
        String name = rawName == null ? "" : rawName.strip();
        if (name.isBlank() || name.length() > 30)
            throw error(HttpStatus.BAD_REQUEST, "INVALID_MEMBER_LEVEL_NAME", "等级名称必须为 1 到 30 个字符");
        long min = points(rawMin), max = points(rawMax);
        if (min > max) throw error(HttpStatus.BAD_REQUEST, "INVALID_MEMBER_LEVEL_RANGE", "最低积分不能大于最高积分");
        if (id != null) requireExisting(id);
        long exclude = id == null ? -1 : id;
        if (jdbc.queryForObject("SELECT COUNT(*) FROM member_levels WHERE name=? COLLATE NOCASE AND id<>?", Integer.class, name, exclude) > 0)
            throw error(HttpStatus.CONFLICT, "MEMBER_LEVEL_NAME_EXISTS", "等级名称已存在");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM member_levels WHERE min_points<=? AND max_points>=? AND id<>?", Integer.class, max, min, exclude) > 0)
            throw error(HttpStatus.CONFLICT, "MEMBER_LEVEL_OVERLAP", "积分区间与其他等级重叠（包含上下限）");
        String now = clock.instant().toString();
        if (id == null) {
            jdbc.update("INSERT INTO member_levels(name,min_points,max_points,created_at,updated_at) VALUES (?,?,?,?,?)", name, min, max, now, now);
            id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        } else {
            jdbc.update("UPDATE member_levels SET name=?,min_points=?,max_points=?,updated_at=? WHERE id=?", name, min, max, now, id);
        }
        return jdbc.queryForMap(PROJECTION + " WHERE id=?", id);
    }

    @Transactional
    public Map<String,Object> delete(long id) {
        requireExisting(id);
        jdbc.update("DELETE FROM member_levels WHERE id=?", id);
        return Map.of("id", id, "deleted", true);
    }

    private void requireExisting(long id) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM member_levels WHERE id=?", Integer.class, id) == 0)
            throw error(HttpStatus.NOT_FOUND, "MEMBER_LEVEL_NOT_FOUND", "等级不存在，请刷新后重试");
    }

    private static long points(BigDecimal value) {
        if (value == null || value.stripTrailingZeros().scale() > 0 || value.signum() < 0 || value.compareTo(BigDecimal.valueOf(9007199254740991L)) > 0)
            throw error(HttpStatus.BAD_REQUEST, "INVALID_MEMBER_LEVEL_RANGE", "积分上下限必须是有效的非负整数");
        return value.longValueExact();
    }
}
