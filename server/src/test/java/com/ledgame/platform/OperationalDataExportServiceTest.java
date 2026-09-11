package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class OperationalDataExportServiceTest {
    private final OperationalDataExportService service = new OperationalDataExportService((JdbcTemplate) null, null);

    @Test
    void writesUtf8BomHeadersAndEscapesCsvWithoutLeakingUnlistedFields() {
        byte[] bytes = service.csv(List.of("姓名", "备注"), List.of(Map.of(
                "name", "测试,\"会员\"", "note", "第一行\n第二行", "password_hash", "secret")), "name", "note");

        assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertThat(text).isEqualTo("姓名,备注\r\n\"测试,\"\"会员\"\"\",\"第一行\n第二行\"\r\n");
        assertThat(text).doesNotContain("secret", "password_hash");
    }

    @Test
    void emptyExportStillContainsOneHeaderRow() {
        byte[] bytes = service.csv(List.of("编号", "名称"), List.of(), "id", "name");
        assertThat(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8)).isEqualTo("编号,名称\r\n");
    }

    @Test
    void writesALargeBatchWithoutChangingRowOrderOrCsvShape() {
        List<Map<String, Object>> rows = java.util.stream.IntStream.range(0, 10_000)
                .mapToObj(index -> Map.<String, Object>of("id", index, "name", "会员" + index))
                .toList();

        byte[] bytes = service.csv(List.of("编号", "姓名"), rows, "id", "name");
        String csv = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);

        assertThat(csv.lines()).hasSize(10_001);
        assertThat(csv).startsWith("编号,姓名\r\n0,会员0\r\n").endsWith("9999,会员9999\r\n");
    }
}
