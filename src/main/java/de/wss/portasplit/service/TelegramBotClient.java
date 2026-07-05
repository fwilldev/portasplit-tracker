package de.wss.portasplit.service;

import com.fasterxml.jackson.databind.JsonNode;
import de.wss.portasplit.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper over the Telegram Bot API: sending messages and short-polling {@code getUpdates} for
 * the interactive opt-in flow. Stateless with respect to who the recipients are — that is the
 * {@link TelegramSubscriberService}'s job; this class only knows how to talk to Telegram.
 */
@Component
public class TelegramBotClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotClient.class);

    /** callback_data sent by the "confirm" inline button offered after {@code /start}. */
    public static final String CONFIRM_CALLBACK = "confirm";

    private final RestClient restClient;
    private final AppProperties props;

    /** Resolved once from {@code getMe}; {@code null} until then / when unavailable. */
    private volatile String cachedUsername;

    public TelegramBotClient(RestClient restClient, AppProperties props) {
        this.restClient = restClient;
        this.props = props;
    }

    /** Whether the bot is switched on and has a token — i.e. the API can be called at all. */
    public boolean canOperate() {
        AppProperties.Telegram t = props.telegram();
        return t.enabled() && StringUtils.hasText(t.botToken());
    }

    private String api(String method) {
        AppProperties.Telegram t = props.telegram();
        return t.apiBase() + "/bot" + t.botToken() + "/" + method;
    }

    /** Sends an HTML-formatted message to one chat. Returns whether Telegram accepted it. */
    public boolean sendHtml(String chatId, String html) {
        return post("sendMessage", Map.of(
                "chat_id", chatId,
                "text", html,
                "parse_mode", "HTML",
                "disable_web_page_preview", true));
    }

    /** Sends a plain-text message to one chat. Returns whether Telegram accepted it. */
    public boolean sendPlain(String chatId, String text) {
        return post("sendMessage", Map.of("chat_id", chatId, "text", text));
    }

    /**
     * Sends a message carrying a single inline "confirm" button (callback_data
     * {@value #CONFIRM_CALLBACK}). Used to turn a {@code /start} into an explicit opt-in.
     */
    public boolean sendConfirmPrompt(String chatId, String text) {
        Map<String, Object> button = Map.of("text", "✅ Bestätigen", "callback_data", CONFIRM_CALLBACK);
        Map<String, Object> markup = Map.of("inline_keyboard", List.of(List.of(button)));
        return post("sendMessage", Map.of("chat_id", chatId, "text", text, "reply_markup", markup));
    }

    /** Acknowledges a callback query so Telegram stops the button's loading spinner. Best-effort. */
    public void answerCallback(String callbackId, String text) {
        post("answerCallbackQuery", Map.of("callback_query_id", callbackId, "text", text));
    }

    /**
     * The bot's {@code @username} (without the leading {@code @}), resolved once via {@code getMe} and
     * cached. {@code null} when the bot is not configured or the call fails.
     */
    public String botUsername() {
        if (!canOperate()) {
            return null;
        }
        String cached = cachedUsername;
        if (cached != null) {
            return cached;
        }
        try {
            JsonNode body = restClient.get().uri(api("getMe")).retrieve().body(JsonNode.class);
            if (body != null && body.path("ok").asBoolean(false)) {
                String username = body.path("result").path("username").asText(null);
                if (StringUtils.hasText(username)) {
                    cachedUsername = username;
                    return username;
                }
            }
        } catch (Exception e) {
            log.debug("Telegram getMe failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Short-polls {@code getUpdates} starting at {@code offset}. Returns the parsed updates (possibly
     * empty); an empty list on any error so the caller simply retries on the next tick without
     * advancing the offset.
     */
    public List<Update> getUpdates(long offset) {
        if (!canOperate()) {
            return List.of();
        }
        try {
            JsonNode body = restClient.get()
                    .uri(api("getUpdates") + "?timeout=0&allowed_updates=%5B%22message%22,%22callback_query%22%5D&offset=" + offset)
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null || !body.path("ok").asBoolean(false)) {
                log.debug("Telegram getUpdates returned not-ok");
                return List.of();
            }
            List<Update> updates = new ArrayList<>();
            for (JsonNode u : body.path("result")) {
                updates.add(parse(u));
            }
            return updates;
        } catch (Exception e) {
            log.debug("Telegram getUpdates failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static Update parse(JsonNode u) {
        long updateId = u.path("update_id").asLong();
        IncomingMessage message = null;
        IncomingCallback callback = null;

        JsonNode msg = u.get("message");
        if (msg != null && msg.hasNonNull("chat")) {
            message = new IncomingMessage(
                    msg.path("chat").path("id").asText(),
                    msg.path("text").asText(""),
                    senderName(msg.get("from")));
        }

        JsonNode cb = u.get("callback_query");
        if (cb != null) {
            JsonNode cbMsg = cb.get("message");
            String chatId = cbMsg != null ? cbMsg.path("chat").path("id").asText(null) : null;
            callback = new IncomingCallback(
                    cb.path("id").asText(),
                    chatId,
                    cb.path("data").asText(""),
                    senderName(cb.get("from")));
        }
        return new Update(updateId, message, callback);
    }

    /** Best-effort human label from a Telegram {@code from} object: full name, else {@code @username}. */
    private static String senderName(JsonNode from) {
        if (from == null) {
            return null;
        }
        String first = from.path("first_name").asText("");
        String last = from.path("last_name").asText("");
        String full = (first + " " + last).trim();
        if (StringUtils.hasText(full)) {
            return full;
        }
        String username = from.path("username").asText(null);
        return StringUtils.hasText(username) ? "@" + username : null;
    }

    private boolean post(String method, Map<String, Object> payload) {
        if (!canOperate()) {
            return false;
        }
        try {
            restClient.post()
                    .uri(api(method))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("Telegram {} failed: {}", method, e.getMessage());
            return false;
        }
    }

    /** A single Telegram update: exactly one of {@code message}/{@code callback} is non-null in practice. */
    public record Update(long updateId, IncomingMessage message, IncomingCallback callback) {
    }

    /** An incoming text message. */
    public record IncomingMessage(String chatId, String text, String senderName) {
    }

    /** An inline-button press. */
    public record IncomingCallback(String id, String chatId, String data, String senderName) {
    }
}
