package de.wss.portasplit.domain;

import java.util.Locale;

/**
 * Lifecycle of a Telegram recipient.
 *
 * <ul>
 *   <li>{@link #PENDING} — opted in via the bot ({@code /start}) but has not yet confirmed; no
 *       automatic alerts are sent to them.</li>
 *   <li>{@link #CONFIRMED} — an active recipient; receives every notification.</li>
 *   <li>{@link #UNSUBSCRIBED} — a soft-deleted tombstone (opted out via {@code /stop} or removed on
 *       the settings page). Kept as a row so the startup seed does not re-add someone who explicitly
 *       left — the database is the source of truth.</li>
 * </ul>
 */
public enum SubscriberState {

    PENDING,
    CONFIRMED,
    UNSUBSCRIBED;

    /** Lenient parse of a stored value; {@code null} for a blank or unknown string. */
    public static SubscriberState fromString(String value) {
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
