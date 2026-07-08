package de.wss.portasplit.service;

import com.fasterxml.jackson.databind.JsonNode;
import de.wss.portasplit.domain.TelegramSubscriber.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Polls the Telegram Bot API for incoming updates and drives the self-service opt-in flow:
 * {@code /start} → confirm button → {@link State#CONFIRMED}, and {@code /stop} → unsubscribe. Runs on a
 * short-polling schedule (no webhook, so it works behind the shared container network). The poll offset
 * is persisted so a restart does not reprocess already-handled updates.
 */
@Component
public class TelegramUpdatePoller {

    private static final Logger log = LoggerFactory.getLogger(TelegramUpdatePoller.class);

    /** Inline-button payload (≤64 bytes) sent with the /start prompt and echoed back on press. */
    private static final String CONFIRM_DATA = "subscribe:confirm";

    private final TelegramBotClient client;
    private final TelegramSubscriberService subscribers;
    private final SettingsService settings;

    public TelegramUpdatePoller(TelegramBotClient client, TelegramSubscriberService subscribers,
                                SettingsService settings) {
        this.client = client;
        this.subscribers = subscribers;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${app.telegram.poll-interval-ms:5000}",
            initialDelayString = "${app.telegram.poll-initial-delay-ms:8000}")
    public void poll() {
        if (!client.canOperate()) {
            return;
        }
        long offset = currentOffset();
        List<JsonNode> updates = client.getUpdates(offset, 0);
        if (updates.isEmpty()) {
            return;
        }
        long maxId = offset - 1;
        for (JsonNode update : updates) {
            long id = update.path("update_id").asLong();
            maxId = Math.max(maxId, id);
            try {
                handle(update);
            } catch (Exception e) {
                log.warn("Failed to handle Telegram update {}: {}", id, e.getMessage());
            }
        }
        // Acknowledge everything we saw so it is not delivered again, even if a handler above threw.
        settings.set(SettingsService.TELEGRAM_UPDATES_OFFSET, Long.toString(maxId + 1));
    }

    private long currentOffset() {
        return settings.get(SettingsService.TELEGRAM_UPDATES_OFFSET)
                .map(v -> {
                    try {
                        return Long.parseLong(v);
                    } catch (NumberFormatException e) {
                        return 0L;
                    }
                })
                .orElse(0L);
    }

    private void handle(JsonNode update) {
        JsonNode callback = update.get("callback_query");
        if (callback != null && !callback.isNull()) {
            handleCallback(callback);
            return;
        }
        JsonNode message = update.get("message");
        if (message != null && !message.isNull()) {
            handleMessage(message);
        }
    }

    private void handleMessage(JsonNode message) {
        JsonNode chat = message.path("chat");
        String chatId = chat.path("id").asText(null);
        if (chatId == null) {
            return;
        }
        String text = message.path("text").asText("").trim();
        // /start may arrive as "/start" or "/start@BotName", possibly with a deep-link payload.
        String command = text.split("\\s+", 2)[0].split("@", 2)[0].toLowerCase();
        String firstName = firstNonBlank(chat.path("first_name").asText(null),
                message.path("from").path("first_name").asText(null));
        String username = firstNonBlank(chat.path("username").asText(null),
                message.path("from").path("username").asText(null));

        switch (command) {
            case "/start" -> {
                State state = subscribers.onStart(chatId, firstName, username);
                if (state == State.CONFIRMED) {
                    client.sendHtml(chatId, "✅ Du bekommst bereits Benachrichtigungen. "
                            + "Zum Abmelden sende /stop.");
                } else {
                    client.sendHtmlWithButton(chatId,
                            "👋 <b>PortaSplit-Tracker</b>\n\n"
                                    + "Möchtest du Benachrichtigungen erhalten, sobald eine PortaSplit "
                                    + "verfügbar wird? Tippe zum Bestätigen auf den Button.",
                            "✅ Ja, Benachrichtigungen aktivieren", CONFIRM_DATA);
                }
            }
            case "/stop" -> {
                subscribers.unsubscribe(chatId);
                client.sendHtml(chatId, "🔕 Abgemeldet. Du bekommst keine Benachrichtigungen mehr. "
                        + "Mit /start kannst du sie jederzeit wieder aktivieren.");
            }
            default -> {
                // Unknown message: gently point to the commands.
                client.sendHtml(chatId, "Sende /start, um Benachrichtigungen zu aktivieren, "
                        + "oder /stop, um sie abzubestellen.");
            }
        }
    }

    private void handleCallback(JsonNode callback) {
        String callbackId = callback.path("id").asText(null);
        String data = callback.path("data").asText("");
        JsonNode chat = callback.path("message").path("chat");
        String chatId = chat.path("id").asText(null);
        if (chatId == null || callbackId == null) {
            return;
        }
        if (!CONFIRM_DATA.equals(data)) {
            client.answerCallback(callbackId, null);
            return;
        }
        String firstName = firstNonBlank(chat.path("first_name").asText(null),
                callback.path("from").path("first_name").asText(null));
        String username = firstNonBlank(chat.path("username").asText(null),
                callback.path("from").path("username").asText(null));
        boolean newlyConfirmed = subscribers.confirm(chatId, firstName, username);
        client.answerCallback(callbackId, newlyConfirmed ? "Aktiviert ✅" : "Bereits aktiv");
        if (newlyConfirmed) {
            client.sendHtml(chatId, "✅ <b>Aktiviert!</b> Du bekommst ab jetzt eine Nachricht, "
                    + "sobald eine PortaSplit verfügbar ist. Zum Abmelden: /stop.");
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return (b != null && !b.isBlank()) ? b : null;
    }
}
