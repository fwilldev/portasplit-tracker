package de.wss.portasplit.web.dto;

import de.wss.portasplit.domain.TelegramSubscriber;

/**
 * One Telegram recipient as shown on the settings page.
 *
 * @param chatId      the recipient's chat id
 * @param displayName human-friendly label (falls back to the chat id when no name is known)
 * @param state       {@code PENDING} or {@code CONFIRMED} (tombstoned recipients are not listed)
 * @param source      {@code ENV} (config seed) or {@code BOT} (self-service opt-in)
 */
public record TelegramSubscriberDto(
        String chatId,
        String displayName,
        String state,
        String source
) {

    public static TelegramSubscriberDto of(TelegramSubscriber s) {
        String name = (s.getDisplayName() != null && !s.getDisplayName().isBlank())
                ? s.getDisplayName()
                : s.getChatId();
        return new TelegramSubscriberDto(s.getChatId(), name, s.getState().name(), s.getSource().name());
    }
}
