package com.ledgame.platform;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class OperatorPasswordConfiguration {
    @Bean
    PasswordEncoder operatorPasswordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
