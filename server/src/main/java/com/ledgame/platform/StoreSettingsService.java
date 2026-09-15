package com.ledgame.platform;

import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoreSettingsService {
    public static final String DEFAULT_TITLE = "LED GAME MEMBER ADMIN";
    public static final int DEFAULT_UNIT_PRICE_CENTS = 100;
    private final JdbcTemplate jdbc;
    private final BrandingStorageService branding;
    private final Clock clock;

    public StoreSettingsService(JdbcTemplate jdbc, BrandingStorageService branding, Clock clock) {
        this.jdbc = jdbc;
        this.branding = branding;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT app_title AS appTitle, app_icon_path AS appIconPath,
                   app_icon_sha256 AS appIconSha256, unit_price_cents AS unitPriceCents,
                   secondary_display_enabled AS secondaryDisplayEnabled, updated_at AS updatedAt
              FROM store_settings WHERE id=1
            """);
        Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);
        String rawTitle = text(row.get("appTitle"));
        String title = rawTitle.isBlank() ? DEFAULT_TITLE : normalizeTitle(rawTitle);
        String iconPath = text(row.get("appIconPath"));
        BrandingStorageService.StoredIcon icon = branding.read(iconPath);
        if (icon == null || !icon.sha256().equals(text(row.get("appIconSha256")))) iconPath = "";
        int unitPrice = positiveInteger(row.get("unitPriceCents"), DEFAULT_UNIT_PRICE_CENTS);
        boolean secondary = number(row.get("secondaryDisplayEnabled")) != 0;
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("appTitle", title);
        result.put("appIconPath", iconPath.isBlank() ? null : iconPath);
        result.put("appIconSha256", iconPath.isBlank() ? null : text(row.get("appIconSha256")));
        result.put("appIconDataUrl", icon == null || iconPath.isBlank() ? null : dataUrl(icon));
        result.put("unitPriceCents", unitPrice);
        result.put("unitPriceYuan", unitPrice / 100.0);
        result.put("secondaryDisplayEnabled", secondary);
        result.put("updatedAt", row.get("updatedAt"));
        return result;
    }

    @Transactional
    public Map<String, Object> update(StoreSettingsPatch patch) {
        Map<String, Object> current = get();
        String title = patch.appTitle() == null ? String.valueOf(current.get("appTitle")) : normalizeTitle(patch.appTitle());
        int unitPrice = patch.unitPriceCents() == null ? number(current.get("unitPriceCents")) : validateUnitPrice(patch.unitPriceCents());
        boolean secondary = patch.secondaryDisplayEnabled() == null
                ? Boolean.TRUE.equals(current.get("secondaryDisplayEnabled")) : patch.secondaryDisplayEnabled();
        String iconPath = (String) current.get("appIconPath");
        String iconSha = (String) current.get("appIconSha256");
        if (Boolean.TRUE.equals(patch.clearIcon())) {
            branding.delete(iconPath);
            iconPath = null;
            iconSha = null;
        } else if (patch.iconImageBase64() != null && !patch.iconImageBase64().isBlank()) {
            BrandingStorageService.StoredIcon icon = branding.store(patch.iconImageBase64(), patch.iconImageMimeType());
            iconPath = icon.path();
            iconSha = icon.sha256();
        }
        String now = clock.instant().toString();
        jdbc.update("""
            INSERT INTO store_settings(
                id, app_title, app_icon_path, app_icon_sha256,
                unit_price_cents, secondary_display_enabled, created_at, updated_at)
            VALUES (1, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET app_title=excluded.app_title,
                app_icon_path=excluded.app_icon_path, app_icon_sha256=excluded.app_icon_sha256,
                unit_price_cents=excluded.unit_price_cents,
                secondary_display_enabled=excluded.secondary_display_enabled,
                updated_at=excluded.updated_at
            """, title, iconPath, iconSha, unitPrice, secondary ? 1 : 0, now, now);
        return get();
    }

    private static String dataUrl(BrandingStorageService.StoredIcon icon) {
        return "data:" + icon.mimeType() + ";base64," + Base64.getEncoder().encodeToString(icon.bytes());
    }

    private static int validateUnitPrice(Integer value) {
        if (value == null || value < 1 || value > 1_000_000) {
            throw new PlatformApiException(HttpStatus.BAD_REQUEST, "UNIT_PRICE_INVALID", "每分钟金额必须是 1 到 1000000 分的整数");
        }
        return value;
    }

    private static int positiveInteger(Object value, int fallback) {
        if (!(value instanceof Number number)) return fallback;
        int integer = number.intValue();
        return integer > 0 ? integer : fallback;
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }

    private static String normalizeTitle(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > 60) {
            throw new PlatformApiException(HttpStatus.BAD_REQUEST, "APP_TITLE_INVALID", "应用标题必须是 1 到 60 个字符");
        }
        return normalized;
    }

    public record StoreSettingsPatch(
            String appTitle,
            String iconImageBase64,
            String iconImageMimeType,
            Boolean clearIcon,
            Integer unitPriceCents,
            Boolean secondaryDisplayEnabled) {}
}
