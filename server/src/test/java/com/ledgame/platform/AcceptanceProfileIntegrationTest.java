package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("acceptance")
class AcceptanceProfileIntegrationTest {
    static final Path temporaryDirectory = Path.of("target", "acceptance-profile-" + UUID.randomUUID()).toAbsolutePath();

    @DynamicPropertySource
    static void acceptanceProperties(DynamicPropertyRegistry registry) {
        registry.add("ACCEPTANCE_PLATFORM_DB_PATH", () -> {
            try {
                Files.createDirectories(temporaryDirectory);
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to create acceptance profile directory", exception);
            }
            return temporaryDirectory.resolve("platform.db").toString();
        });
        registry.add("ACCEPTANCE_CLOCK_OFFSET_SECONDS", () -> "120");
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    DataSource dataSource;

    @Autowired
    Clock clock;

    @Test
    void usesRunOwnedSqliteAndExposesReadiness() throws Exception {
        assertThat(dataSource.getConnection().getMetaData().getURL().replace('\\', '/'))
                .contains(temporaryDirectory.resolve("platform.db").toString().replace('\\', '/'));
        @SuppressWarnings("unchecked")
        Map<String, Object> health = rest.getForObject("/api/health", Map.class);
        assertThat(health).containsEntry("ok", true).containsEntry("database", "sqlite");
        assertThat(Duration.between(Instant.now(), clock.instant()).getSeconds()).isBetween(118L, 121L);
    }
}
