package de.wss.portasplit.web.dto;

import java.util.List;

/**
 * The Telegram recipient overview for the settings UI.
 *
 * @param botLink     a {@code https://t.me/<bot>} deep link to share for opt-in, or null if the bot
 *                    username could not be resolved (e.g. Telegram not configured)
 * @param confirmed   number of recipients currently receiving alerts
 * @param subscribers all non-removed recipients (pending + confirmed), oldest first
 */
public record TelegramSubscribersDto(
        String botLink,
        long confirmed,
        List<TelegramSubscriberDto> subscribers
) {
}
