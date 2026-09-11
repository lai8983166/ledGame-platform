package com.ledgame.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ledgame.data-protection")
public class DataProtectionProperties {
    private String keyPath = "";
    private String testKeyBase64 = "";

    public String getKeyPath() { return keyPath; }
    public void setKeyPath(String keyPath) { this.keyPath = keyPath == null ? "" : keyPath; }
    public String getTestKeyBase64() { return testKeyBase64; }
    public void setTestKeyBase64(String testKeyBase64) {
        this.testKeyBase64 = testKeyBase64 == null ? "" : testKeyBase64;
    }
}
