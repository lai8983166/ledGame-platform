package com.ledgame.platform;

import java.time.Clock;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class OperatorAccountBootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final OperatorAccountProperties properties;
    private final Clock clock;

    public OperatorAccountBootstrap(
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder,
            OperatorAccountProperties properties,
            Clock clock) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM operator_accounts", Integer.class);
        if (count != null && count > 0) {
            return;
        }

        OperatorAccountProperties.Factory factory = properties.getFactory();
        String username = requireConfigured(factory.getUsername(), "username");
        String password = requireConfigured(factory.getPassword(), "password");
        String displayName = requireConfigured(factory.getDisplayName(), "display-name");
        String now = clock.instant().toString();
        jdbc.update("""
            INSERT INTO operator_accounts(
                username, display_name, password_hash, account_type,
                enabled, created_by_operator_id, created_at, updated_at)
            VALUES (?, ?, ?, 'FACTORY_ADMIN', 1, NULL, ?, ?)
            """, username, displayName, passwordEncoder.encode(password), now, now);
    }

    private static String requireConfigured(String rawValue, String propertyName) {
        String value = rawValue == null ? "" : rawValue.trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("ledgame.operator-accounts.factory." + propertyName + " must not be blank");
        }
        return value;
    }
}
