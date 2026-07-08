package de.wss.portasplit.web.dto;

import de.wss.portasplit.domain.TelegramSubscriber;

/**
 * One Telegram recipient as shown in the settings UI.
 *
 * @param chatId      the chat id (== user id for private chats)
 * @param displayName best-effort label (first name / @username / id)
 * @param username    Telegram @username, if known
 * @param state       PENDING (awaiting confirmation) or CONFIRMED (receiving alerts)
 * @param source      ENV (seeded from config) or BOT (self-service opt-in)
 */
public record TelegramSubscriberDto(
        String chatId,
        String displayName,
        String username,
        String state,
        String source
) {
    public static TelegramSubscriberDto of(TelegramSubscriber s) {
        return new TelegramSubscriberDto(
                s.getChatId(),
                s.displayName(),
                s.getUsername(),
                s.getState().name(),
                s.getSource().name());
    }
}
