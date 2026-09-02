package com.ledgame.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ledgame.database-backup")
public class DatabaseBackupProperties {
    private boolean enabled = true;
    private String environment = "PRODUCTION";
    private String rootOverride;
    private long pollMillis = 1000;
    private long debounceMillis = 1000;
    private long maxDirtyMillis = 5000;
    private long retryMillis = 15000;
    private long shutdownTimeoutMillis = 15000;
    private int retentionDays = 30;
    private long minimumFreeBytes = 100L * 1024 * 1024;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) {
        String normalized = environment == null ? "" : environment.trim().toUpperCase(java.util.Locale.ROOT);
        if (!("PRODUCTION".equals(normalized) || "TEST".equals(normalized))) {
            throw new IllegalArgumentException("database backup environment must be PRODUCTION or TEST");
        }
        this.environment = normalized;
    }
    public String getRootOverride() { return rootOverride; }
    public void setRootOverride(String rootOverride) { this.rootOverride = rootOverride; }
    public long getPollMillis() { return pollMillis; }
    public void setPollMillis(long pollMillis) { this.pollMillis = pollMillis; }
    public long getDebounceMillis() { return debounceMillis; }
    public void setDebounceMillis(long debounceMillis) { this.debounceMillis = debounceMillis; }
    public long getMaxDirtyMillis() { return maxDirtyMillis; }
    public void setMaxDirtyMillis(long maxDirtyMillis) { this.maxDirtyMillis = maxDirtyMillis; }
    public long getRetryMillis() { return retryMillis; }
    public void setRetryMillis(long retryMillis) { this.retryMillis = retryMillis; }
    public long getShutdownTimeoutMillis() { return shutdownTimeoutMillis; }
    public void setShutdownTimeoutMillis(long shutdownTimeoutMillis) { this.shutdownTimeoutMillis = shutdownTimeoutMillis; }
    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
    public long getMinimumFreeBytes() { return minimumFreeBytes; }
    public void setMinimumFreeBytes(long minimumFreeBytes) { this.minimumFreeBytes = minimumFreeBytes; }
}
