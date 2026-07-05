package de.wss.portasplit.domain;

import java.util.Locale;

/**
 * How a Telegram recipient entered the list.
 *
 * <ul>
 *   <li>{@link #ENV} — seeded on startup from the {@code app.telegram.chat-id} config.</li>
 *   <li>{@link #BOT} — self-service opt-in through the bot ({@code /start} + confirm).</li>
 * </ul>
 */
public enum SubscriberSource {

    ENV,
    BOT;

    /** Lenient parse of a stored value; {@code null} for a blank or unknown string. */
    public static SubscriberSource fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
