package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class OperationalDataExportServiceTest {
    private final OperationalDataExportService service = new OperationalDataExportService((JdbcTemplate) null);

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
}
