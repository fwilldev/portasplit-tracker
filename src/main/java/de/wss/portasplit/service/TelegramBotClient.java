package de.wss.portasplit.service;

import com.fasterxml.jackson.databind.JsonNode;
import de.wss.portasplit.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Thin wrapper over the raw Telegram Bot API HTTP methods (send / receive). Higher-level concerns
 * (which recipients, notification on/off) live in {@link TelegramService} and the poller; this class
 * only knows how to talk to {@code api.telegram.org}.
 */
@Component
public class TelegramBotClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotClient.class);

    private final RestClient restClient;
    private final AppProperties props;

    /** Cached bot @username from getMe (never changes for a token); resolved lazily. */
    private volatile String cachedUsername;

    public TelegramBotClient(RestClient restClient, AppProperties props) {
        this.restClient = restClient;
        this.props = props;
    }

    /** Whether the integration is switched on and has a bot token (required to send or poll). */
    public boolean canOperate() {
        AppProperties.Telegram t = props.telegram();
        return t.enabled() && StringUtils.hasText(t.botToken());
    }

    private String method(String name) {
        AppProperties.Telegram t = props.telegram();
        return t.apiBase() + "/bot" + t.botToken() + "/" + name;
    }

    /** Sends an HTML message to one chat. Returns whether Telegram accepted it. */
    public boolean sendHtml(String chatId, String html) {
        return send(chatId, html, null);
    }

    /**
     * Sends an HTML message carrying a single inline button. {@code callbackData} (max 64 bytes) is
     * echoed back in the resulting {@code callback_query} when the button is pressed.
     */
    public boolean sendHtmlWithButton(String chatId, String html, String buttonText, String callbackData) {
        Map<String, Object> markup = Map.of(
                "inline_keyboard", List.of(List.of(Map.of(
                        "text", buttonText,
                        "callback_data", callbackData
                ))));
        return send(chatId, html, markup);
    }

    private boolean send(String chatId, String html, Map<String, Object> replyMarkup) {
        if (!canOperate()) {
            return false;
        }
        var body = new java.util.HashMap<String, Object>();
        body.put("chat_id", chatId);
        body.put("text", html);
        body.put("parse_mode", "HTML");
        body.put("disable_web_page_preview", true);
        if (replyMarkup != null) {
            body.put("reply_markup", replyMarkup);
        }
        try {
            restClient.post()
                    .uri(method("sendMessage"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("Failed to send Telegram message to chat {}: {}", chatId, e.getMessage());
            return false;
        }
    }

    /** The bot's {@code @username} (without the @), resolved once via getMe. Null when unavailable. */
    public String botUsername() {
        if (!canOperate()) {
            return null;
        }
        String cached = cachedUsername;
        if (cached != null) {
            return cached;
        }
        try {
            JsonNode resp = restClient.get()
                    .uri(method("getMe"))
                    .retrieve()
                    .body(JsonNode.class);
            if (resp != null && resp.path("ok").asBoolean(false)) {
                String username = resp.path("result").path("username").asText(null);
                if (username != null && !username.isBlank()) {
                    cachedUsername = username;
                    return username;
                }
            }
        } catch (Exception e) {
            log.warn("Telegram getMe failed: {}", e.getMessage());
        }
        return null;
    }

    /** Acknowledges a callback query so the client stops showing its progress spinner. */
    public void answerCallback(String callbackQueryId, String text) {
        if (!canOperate()) {
            return;
        }
        try {
            restClient.post()
                    .uri(method("answerCallbackQuery"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(text == null
                            ? Map.of("callback_query_id", callbackQueryId)
                            : Map.of("callback_query_id", callbackQueryId, "text", text))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Failed to answer Telegram callback {}: {}", callbackQueryId, e.getMessage());
        }
    }

    /**
     * Long/short-polls for incoming updates. Returns the {@code result} array (possibly empty), or an
     * empty list on error. {@code offset} should be the highest processed {@code update_id} + 1.
     */
    public List<JsonNode> getUpdates(long offset, int timeoutSeconds) {
        if (!canOperate()) {
            return List.of();
        }
        try {
            JsonNode resp = restClient.post()
                    .uri(method("getUpdates"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "offset", offset,
                            "timeout", timeoutSeconds,
                            "limit", 100,
                            "allowed_updates", List.of("message", "callback_query")
                    ))
                    .retrieve()
                    .body(JsonNode.class);
            if (resp == null || !resp.path("ok").asBoolean(false)) {
                if (resp != null) {
                    log.warn("Telegram getUpdates not ok: {}", resp.path("description").asText(""));
                }
                return List.of();
            }
            JsonNode result = resp.path("result");
            if (!result.isArray()) {
                return List.of();
            }
            List<JsonNode> updates = new java.util.ArrayList<>(result.size());
            result.forEach(updates::add);
            return updates;
        } catch (Exception e) {
            log.warn("Telegram getUpdates failed: {}", e.getMessage());
            return List.of();
        }
    }
}
