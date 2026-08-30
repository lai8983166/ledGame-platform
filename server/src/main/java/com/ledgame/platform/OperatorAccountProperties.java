package com.ledgame.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ledgame.operator-accounts")
public class OperatorAccountProperties {
    private final Factory factory = new Factory();

    public Factory getFactory() {
        return factory;
    }

    public static class Factory {
        private String username = "admin";
        private String password = "888888";
        private String displayName = "出厂管理员";

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }
    }
}
