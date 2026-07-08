package de.wss.portasplit.service;

import de.wss.portasplit.domain.AppSetting;
import de.wss.portasplit.repository.AppSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Optional;

/**
 * Runtime-editable settings backed by the database, so toggles and credentials can be updated from
 * the dashboard without a restart.
 */
@Service
public class SettingsService {

    /** Bounded, comma-separated list of recently-notified kleinanzeigen ad ids (de-duplication). */
    public static final String KLEINANZEIGEN_NOTIFIED_IDS = "kleinanzeigen.notifiedAdIds";
    /**
     * Runtime override for the kleinanzeigen search-results URL. When set it takes precedence over the
     * static {@code app.kleinanzeigen.url} config; when neither is set the watcher is disabled.
     */
    public static final String KLEINANZEIGEN_URL = "kleinanzeigen.url";
    /** Prefix for the per-source runtime enable flags ({@code worker.enabled.<JOBTYPE>}). */
    public static final String WORKER_ENABLED_PREFIX = "worker.enabled.";
    /** Runtime master switch for sending Telegram notifications (independent of the static config). */
    public static final String TELEGRAM_NOTIFY = "telegram.notify";
    /**
     * Opt-in switch for alerting on the "in-store stock exists but online reservation is disabled"
     * case (e.g. Bauhaus freight items you can only reserve by phone/in person). Default off, so the
     * standard behaviour stays "only notify on genuinely online-reservable stock".
     */
    public static final String TELEGRAM_NOTIFY_CALL_ONLY = "telegram.notifyCallOnly";
    /** Highest processed Telegram {@code update_id} + 1 — the getUpdates poll offset (survives restarts). */
    public static final String TELEGRAM_UPDATES_OFFSET = "telegram.updates.offset";

    // --- toom auto-reserve (separate feature from scraping; requires a logged-in toom account) ------
    /** Runtime on/off switch for the toom auto-reserve feature. Default off. */
    public static final String TOOM_AUTORESERVE_ENABLED = "toom.autoReserve.enabled";
    /** Stored toom account e-mail (plain). */
    public static final String TOOM_AUTH_EMAIL = "toom.auth.email";
    /** Stored toom account password, AES-encrypted at rest (Base64). */
    public static final String TOOM_AUTH_PASSWORD_ENC = "toom.auth.passwordEnc";

    // --- Umkreissuche (radius search) -----------------------------------------------------------
    /** Whether the radius filter restricts which branches are displayed and notified. */
    public static final String RADIUS_ENABLED = "radius.enabled";
    /** Radius in kilometres around the centre. */
    public static final String RADIUS_KM = "radius.km";
    /** Centre latitude (resolved from the entered PLZ, or set directly). */
    public static final String RADIUS_CENTER_LAT = "radius.center.lat";
    /** Centre longitude. */
    public static final String RADIUS_CENTER_LON = "radius.center.lon";
    /** Human-readable label for the centre (e.g. the PLZ), shown in the dashboard. */
    public static final String RADIUS_CENTER_LABEL = "radius.center.label";

    private final AppSettingRepository repo;

    public SettingsService(AppSettingRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public Optional<String> get(String key) {
        return repo.findById(key).map(AppSetting::getValue).filter(StringUtils::hasText);
    }

    @Transactional(readOnly = true)
    public Optional<Instant> updatedAt(String key) {
        return repo.findById(key).map(AppSetting::getUpdatedAt);
    }

    @Transactional
    public void set(String key, String value) {
        if (!StringUtils.hasText(value)) {
            repo.deleteById(key);
            return;
        }
        AppSetting s = repo.findById(key).orElseGet(() -> new AppSetting(key));
        s.setValue(value.trim());
        s.setUpdatedAt(Instant.now());
        repo.save(s);
    }

    /**
     * Whether Telegram notifications should be sent. A runtime override set from the settings page
     * takes precedence; when unset it defaults to {@code true} (i.e. send whenever Telegram is
     * configured). This only gates automatic alerts — the explicit "Telegram testen" action ignores it.
     */
    public boolean telegramNotifyEnabled() {
        return get(TELEGRAM_NOTIFY).map(Boolean::parseBoolean).orElse(true);
    }

    /** Whether the toom auto-reserve feature is switched on (dashboard). Defaults to off. */
    public boolean toomAutoReserveEnabled() {
        return get(TOOM_AUTORESERVE_ENABLED).map(Boolean::parseBoolean).orElse(false);
    }

    /**
     * Whether to also alert when a branch has pickup stock that cannot be reserved online (only by
     * phone/in person). Opt-in; defaults to off so alerts stay limited to online-reservable stock.
     */
    public boolean callOnlyNotifyEnabled() {
        return get(TELEGRAM_NOTIFY_CALL_ONLY).map(Boolean::parseBoolean).orElse(false);
    }

    @Transactional
    public void putBool(String key, boolean value) {
        AppSetting s = repo.findById(key).orElseGet(() -> new AppSetting(key));
        s.setValue(Boolean.toString(value));
        s.setUpdatedAt(Instant.now());
        repo.save(s);
    }
}
