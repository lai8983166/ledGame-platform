package com.ledgame.platform;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Builds genuine pre-plaintext databases; production migration must not generate these. */
final class LegacyEncryptedFixture {
    static void encrypt(JdbcTemplate jdbc, DataKeyMaterial key) {
        var codec = ProtectedDataService.forKey(key);
        for (String field : List.of("members.phone", "members.name", "members.avatar_id", "members.birthday", "members.gender",
                "wristbands.card_uid", "wristband_charge_records.wristband_uid", "wristband_charge_records.operator_username",
                "wristband_charge_records.operator_display_name", "game_play_records.wristband_uid", "game_play_records.result_json",
                "operator_action_logs.operator_username", "operator_action_logs.operator_display_name", "operator_action_logs.target_id", "operator_action_logs.summary_json")) {
            var parts = field.split("\\.");
            for (var row : jdbc.queryForList("SELECT id," + parts[1] + " AS value FROM " + parts[0] + " WHERE " + parts[1] + " IS NOT NULL")) {
                jdbc.update("UPDATE " + parts[0] + " SET " + parts[1] + "=? WHERE id=?", codec.encrypt(field, String.valueOf(row.get("value"))), row.get("id"));
            }
        }
        jdbc.update("UPDATE data_protection_state SET format_version=1,key_id=?,status='COMPLETE' WHERE id=1",key.keyId());
    }
}
