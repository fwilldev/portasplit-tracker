package de.wss.portasplit.web;

import de.wss.portasplit.jobs.JobType;
import de.wss.portasplit.service.IntervalSettings;
import de.wss.portasplit.service.KleinanzeigenService;
import de.wss.portasplit.service.PlzGeocoder;
import de.wss.portasplit.service.RadiusService;
import de.wss.portasplit.service.SettingsService;
import de.wss.portasplit.service.TelegramBotClient;
import de.wss.portasplit.service.TelegramService;
import de.wss.portasplit.service.TelegramSubscriberService;
import de.wss.portasplit.web.dto.IntervalSettingDto;
import de.wss.portasplit.web.dto.IntervalUpdateRequest;
import de.wss.portasplit.web.dto.KleinanzeigenSettingsDto;
import de.wss.portasplit.web.dto.KleinanzeigenSettingsRequest;
import de.wss.portasplit.web.dto.NotificationSettingsDto;
import de.wss.portasplit.web.dto.NotificationSettingsRequest;
import de.wss.portasplit.web.dto.RadiusSettingsDto;
import de.wss.portasplit.web.dto.RadiusSettingsRequest;
import de.wss.portasplit.web.dto.TelegramSubscriberDto;
import de.wss.portasplit.web.dto.TelegramSubscribersDto;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settings;
    private final TelegramService telegram;
    private final TelegramSubscriberService telegramSubscribers;
    private final TelegramBotClient telegramBot;
    private final RadiusService radiusService;
    private final PlzGeocoder geocoder;
    private final IntervalSettings intervals;
    private final KleinanzeigenService kleinanzeigen;

    public SettingsController(SettingsService settings,
                              TelegramService telegram,
                              TelegramSubscriberService telegramSubscribers,
                              TelegramBotClient telegramBot, RadiusService radiusService,
                              PlzGeocoder geocoder, IntervalSettings intervals,
                              KleinanzeigenService kleinanzeigen) {
        this.settings = settings;
        this.telegram = telegram;
        this.telegramSubscribers = telegramSubscribers;
        this.telegramBot = telegramBot;
        this.radiusService = radiusService;
        this.geocoder = geocoder;
        this.intervals = intervals;
        this.kleinanzeigen = kleinanzeigen;
    }

    /** Every source's configurable poll interval (effective values + config defaults), in job order. */
    @GetMapping("/intervals")
    public List<IntervalSettingDto> getIntervals() {
        return Arrays.stream(JobType.values())
                .map(t -> IntervalSettingDto.of(t, intervals))
                .toList();
    }

    /**
     * Updates one source's poll interval (partial: unspecified fields stay unchanged), or resets it to
     * the static config default when {@code reset} is true. Returns the refreshed setting for that
     * source. Invalid combinations (min below the floor, max below min) yield a 400.
     */
    @PutMapping("/intervals/{type}")
    public IntervalSettingDto updateInterval(@PathVariable JobType type, @RequestBody IntervalUpdateRequest req) {
        if (Boolean.TRUE.equals(req.reset())) {
            intervals.reset(type);
        } else {
            try {
                intervals.update(type, req.minIntervalMs(), req.maxIntervalMs(), req.initialDelayMs());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
            }
        }
        return IntervalSettingDto.of(type, intervals);
    }

    @GetMapping("/notifications")
    public NotificationSettingsDto getNotifications() {
        return new NotificationSettingsDto(telegram.isConfigured(),
                settings.telegramNotifyEnabled(), settings.callOnlyNotifyEnabled());
    }

    @PutMapping("/notifications")
    public NotificationSettingsDto updateNotifications(@RequestBody NotificationSettingsRequest req) {
        if (req.telegramNotify() != null) {
            settings.putBool(SettingsService.TELEGRAM_NOTIFY, req.telegramNotify());
        }
        if (req.notifyCallOnly() != null) {
            settings.putBool(SettingsService.TELEGRAM_NOTIFY_CALL_ONLY, req.notifyCallOnly());
        }
        return new NotificationSettingsDto(telegram.isConfigured(),
                settings.telegramNotifyEnabled(), settings.callOnlyNotifyEnabled());
    }

    /** The Telegram recipients (pending + confirmed) plus a shareable opt-in bot link. */
    @GetMapping("/telegram/subscribers")
    public TelegramSubscribersDto getTelegramSubscribers() {
        String username = telegramBot.botUsername();
        String botLink = username != null ? "https://t.me/" + username : null;
        List<TelegramSubscriberDto> list = telegramSubscribers.activeSubscribers().stream()
                .map(TelegramSubscriberDto::of)
                .toList();
        return new TelegramSubscribersDto(botLink, telegramSubscribers.confirmedCount(), list);
    }

    /** Removes a recipient (opt-out). Idempotent; unknown ids are a no-op. */
    @DeleteMapping("/telegram/subscribers/{chatId}")
    public TelegramSubscribersDto removeTelegramSubscriber(@PathVariable String chatId) {
        telegramSubscribers.unsubscribe(chatId);
        return getTelegramSubscribers();
    }

    @GetMapping("/kleinanzeigen")
    public KleinanzeigenSettingsDto getKleinanzeigen() {
        return buildKleinanzeigenDto();
    }

    /**
     * Sets or clears the kleinanzeigen search URL. A blank URL clears the runtime override (falling back
     * to the static config, which may be empty → the watcher is then disabled). No link means disabled.
     */
    @PutMapping("/kleinanzeigen")
    public KleinanzeigenSettingsDto updateKleinanzeigen(@RequestBody KleinanzeigenSettingsRequest req) {
        // set() deletes the key when the value is blank, so this both sets and clears the override.
        settings.set(SettingsService.KLEINANZEIGEN_URL, req.url() == null ? "" : req.url());
        return buildKleinanzeigenDto();
    }

    private KleinanzeigenSettingsDto buildKleinanzeigenDto() {
        boolean overridden = settings.get(SettingsService.KLEINANZEIGEN_URL).isPresent();
        return new KleinanzeigenSettingsDto(kleinanzeigen.effectiveUrl(), kleinanzeigen.enabled(), overridden);
    }

    @GetMapping("/radius")
    public RadiusSettingsDto getRadius() {
        return buildRadiusDto();
    }

    /**
     * Updates the Umkreissuche. The centre may be given as a PLZ (geocoded offline against the bundled
     * dataset) or as explicit coordinates; an unknown PLZ is rejected with 400. Branches outside the
     * radius are still scraped — only display and Telegram alerts are restricted.
     */
    @PutMapping("/radius")
    public RadiusSettingsDto updateRadius(@RequestBody RadiusSettingsRequest req) {
        if (req.enabled() != null) {
            settings.putBool(SettingsService.RADIUS_ENABLED, req.enabled());
        }
        if (req.km() != null) {
            settings.set(SettingsService.RADIUS_KM, String.valueOf(Math.max(0.0, req.km())));
        }
        if (StringUtils.hasText(req.plz())) {
            String plz = PlzGeocoder.normalizePlz(req.plz());
            double[] c = geocoder.coordinates(req.plz()).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "PLZ „" + req.plz().trim() + "“ nicht gefunden"));
            settings.set(SettingsService.RADIUS_CENTER_LAT, String.valueOf(c[0]));
            settings.set(SettingsService.RADIUS_CENTER_LON, String.valueOf(c[1]));
            settings.set(SettingsService.RADIUS_CENTER_LABEL,
                    StringUtils.hasText(req.label()) ? req.label().trim() : plz);
        } else if (req.centerLat() != null && req.centerLon() != null) {
            settings.set(SettingsService.RADIUS_CENTER_LAT, String.valueOf(req.centerLat()));
            settings.set(SettingsService.RADIUS_CENTER_LON, String.valueOf(req.centerLon()));
            settings.set(SettingsService.RADIUS_CENTER_LABEL, StringUtils.hasText(req.label())
                    ? req.label().trim()
                    : String.format(java.util.Locale.ROOT, "%.4f, %.4f", req.centerLat(), req.centerLon()));
        } else if (req.label() != null) {
            settings.set(SettingsService.RADIUS_CENTER_LABEL, req.label().trim());
        }
        return buildRadiusDto();
    }

    private RadiusSettingsDto buildRadiusDto() {
        RadiusService.RadiusConfig c = radiusService.config();
        boolean centerResolved = c.centerLat() != null && c.centerLon() != null;
        return new RadiusSettingsDto(c.enabled(), c.km(), c.centerLat(), c.centerLon(), c.label(),
                centerResolved, geocoder.isLoaded());
    }
}
