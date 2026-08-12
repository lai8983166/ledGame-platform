package com.ledgame.platform;

import java.time.Clock;
import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PlatformClockConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock platformClock(@Value("${ledgame.acceptance.clock-offset-seconds:0}") long offsetSeconds) {
        return Clock.offset(Clock.systemUTC(), Duration.ofSeconds(offsetSeconds));
    }
}
