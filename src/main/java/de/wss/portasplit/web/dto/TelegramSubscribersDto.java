package de.wss.portasplit.web.dto;

import java.util.List;

/**
 * The Telegram recipients view for the settings page.
 *
 * @param botLink     shareable opt-in link ({@code https://t.me/<bot>}); {@code null} when the bot
 *                    username could not be resolved (e.g. Telegram not configured)
 * @param confirmed   number of confirmed (active) recipients
 * @param subscribers all active recipients (pending + confirmed), oldest first
 */
public record TelegramSubscribersDto(
        String botLink,
        long confirmed,
        List<TelegramSubscriberDto> subscribers
) {
}
