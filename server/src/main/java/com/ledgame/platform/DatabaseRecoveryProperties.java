package com.ledgame.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the vendor-assisted disaster-recovery key envelope. */
@ConfigurationProperties(prefix = "ledgame.database-recovery")
public class DatabaseRecoveryProperties {
    private boolean enabled = true;
    private String recoveryKeyId = "factory-recovery-v1";
    private String publicKeyPath = "";
    private String publicKeyResource = "database-recovery-public.pem";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getRecoveryKeyId() { return recoveryKeyId; }
    public void setRecoveryKeyId(String recoveryKeyId) {
        if (recoveryKeyId == null || recoveryKeyId.isBlank() || recoveryKeyId.length() > 128) {
            throw new IllegalArgumentException("database recovery key id is invalid");
        }
        this.recoveryKeyId = recoveryKeyId.trim();
    }

    public String getPublicKeyPath() { return publicKeyPath; }
    public void setPublicKeyPath(String publicKeyPath) {
        this.publicKeyPath = publicKeyPath == null ? "" : publicKeyPath.trim();
    }

    public String getPublicKeyResource() { return publicKeyResource; }
    public void setPublicKeyResource(String publicKeyResource) {
        this.publicKeyResource = publicKeyResource == null ? "" : publicKeyResource.trim();
    }
}
