package de.wss.portasplit.service;

import de.wss.portasplit.domain.Product;
import de.wss.portasplit.domain.Shop;
import de.wss.portasplit.kleinanzeigen.KleinanzeigenListing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/** Builds and dispatches availability notifications. */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final TelegramService telegram;

    public NotificationService(TelegramService telegram) {
        this.telegram = telegram;
    }

    /**
     * Formats a EUR amount. A fresh {@link NumberFormat} per call because the formatter is not
     * thread-safe and notifications now run on independent poller threads (and the manual-check
     * {@code CompletableFuture}s).
     */
    private static String formatEur(BigDecimal value) {
        return NumberFormat.getCurrencyInstance(Locale.GERMANY).format(value);
    }

    /** Fired when a product becomes available at a shop. */
    public void notifyAvailable(Shop shop, Product product, Integer stock, BigDecimal price,
                                String url, String note) {
        StringBuilder sb = new StringBuilder();
        sb.append("🟢 <b>").append(TelegramService.escape(product.displayName()))
                .append(" verfügbar!</b>\n\n");
        sb.append("🏬 ").append(TelegramService.escape(shop.getName())).append('\n');
        if (StringUtils.hasText(shop.getCity())) {
            sb.append("📍 ")
                    .append(TelegramService.escape(joinAddress(shop)))
                    .append('\n');
        }
        // Amazon has no meaningful stock count; show the delivery/Prime note instead.
        if (stock != null) {
            sb.append("📦 Bestand: ").append(stock).append('\n');
        }
        if (StringUtils.hasText(note)) {
            sb.append("🚚 ").append(TelegramService.escape(note)).append('\n');
        }
        if (price != null) {
            sb.append("💶 Preis: ").append(TelegramService.escape(formatEur(price))).append('\n');
        }
        if (StringUtils.hasText(url)) {
            sb.append("🔗 <a href=\"").append(TelegramService.escape(url)).append("\">Zum Artikel</a>");
        }
        boolean sent = telegram.sendNotification(sb.toString());
        log.info("Availability notification for {} / {} (stock={}, note={}): telegram sent={}",
                shop.getName(), product.displayName(), stock, note, sent);
    }

    /**
     * Fired when a product appears in a branch with pickup stock that cannot be reserved online — the
     * only way to secure it is by phone/in person (e.g. Bauhaus freight items). Distinct from
     * {@link #notifyAvailable}: opt-in via {@code SettingsService.callOnlyNotifyEnabled()}.
     */
    public void notifyCallOnly(Shop shop, Product product, Integer stock, BigDecimal price,
                               String url, String reserveIssueNote) {
        StringBuilder sb = new StringBuilder();
        sb.append("🟡 <b>").append(TelegramService.escape(product.displayName()))
                .append(" im Markt vorrätig</b>\n");
        sb.append("<i>nur telefonisch / vor Ort reservierbar — online nicht möglich</i>\n\n");
        sb.append("🏬 ").append(TelegramService.escape(shop.getName())).append('\n');
        if (StringUtils.hasText(shop.getCity())) {
            sb.append("📍 ")
                    .append(TelegramService.escape(joinAddress(shop)))
                    .append('\n');
        }
        if (stock != null) {
            sb.append("📦 Bestand: ").append(stock).append('\n');
        }
        if (StringUtils.hasText(reserveIssueNote)) {
            sb.append("ℹ️ ").append(TelegramService.escape(reserveIssueNote)).append('\n');
        }
        if (price != null) {
            sb.append("💶 Preis: ").append(TelegramService.escape(formatEur(price))).append('\n');
        }
        if (StringUtils.hasText(url)) {
            sb.append("🔗 <a href=\"").append(TelegramService.escape(url)).append("\">Zum Artikel</a>");
        }
        boolean sent = telegram.sendNotification(sb.toString());
        log.info("Call-only notification for {} / {} (stock={}): telegram sent={}",
                shop.getName(), product.displayName(), stock, sent);
    }

    /**
     * Fired when a freshly-posted kleinanzeigen offer is seen for the first time.
     *
     * @return whether Telegram accepted the message. The caller must only mark the offer as
     *         "notified" when this is {@code true}, so a transient send failure is retried on the
     *         next poll instead of silently swallowing the offer.
     */
    public boolean notifyNewListing(KleinanzeigenListing listing) {
        // When notifications are off, treat the offer as handled so it is recorded as seen and won't
        // produce a backlog of alerts the moment notifications are switched back on.
        if (!telegram.notificationsEnabled()) {
            log.info("Telegram notifications disabled; marking listing {} as seen without sending",
                    listing.adId());
            return true;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("🆕 <b>Neues Kleinanzeigen-Angebot!</b>\n\n");
        if (StringUtils.hasText(listing.title())) {
            sb.append("🏷️ ").append(TelegramService.escape(listing.title())).append('\n');
        }
        String price = StringUtils.hasText(listing.priceText())
                ? listing.priceText()
                : (listing.price() != null ? formatEur(listing.price()) : null);
        if (StringUtils.hasText(price)) {
            sb.append("💶 ").append(TelegramService.escape(price)).append('\n');
        }
        if (StringUtils.hasText(listing.location())) {
            sb.append("📍 ").append(TelegramService.escape(listing.location())).append('\n');
        }
        if (StringUtils.hasText(listing.postedText())) {
            sb.append("🕒 ").append(TelegramService.escape(listing.postedText())).append('\n');
        }
        if (StringUtils.hasText(listing.url())) {
            sb.append("🔗 <a href=\"").append(TelegramService.escape(listing.url()))
                    .append("\">Zum Angebot</a>");
        }
        boolean sent = telegram.sendHtml(sb.toString());
        log.info("New listing notification for kleinanzeigen ad {} ({}): telegram sent={}",
                listing.adId(), listing.title(), sent);
        return sent;
    }

    /** Sends a test message; returns whether Telegram accepted it. */
    public boolean sendTest() {
        return telegram.sendHtml("✅ <b>PortaSplit-Tracker</b>\nTelegram ist korrekt konfiguriert.");
    }

    private static String joinAddress(Shop shop) {
        StringBuilder a = new StringBuilder();
        if (StringUtils.hasText(shop.getStreet())) {
            a.append(shop.getStreet()).append(", ");
        }
        if (StringUtils.hasText(shop.getPlz())) {
            a.append(shop.getPlz()).append(' ');
        }
        if (StringUtils.hasText(shop.getCity())) {
            a.append(shop.getCity());
        }
        return a.toString().trim();
    }
}
