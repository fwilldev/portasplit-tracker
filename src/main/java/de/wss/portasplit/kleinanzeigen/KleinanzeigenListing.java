package de.wss.portasplit.kleinanzeigen;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A single offer parsed from a kleinanzeigen.de search-results page.
 *
 * @param adId             stable kleinanzeigen ad id (used to de-duplicate notifications).
 * @param title            ad title.
 * @param url              absolute link to the ad.
 * @param price            parsed price, or {@code null} if not a fixed price ("VB"/"Zu verschenken").
 * @param priceText        raw price label as shown ("950 € VB"), or {@code null}.
 * @param location         seller location ("10176 Berlin"), or {@code null}.
 * @param postedText       raw posting label as shown ("Heute, 14:23"), or {@code null}.
 * @param postedEpochMillis epoch millis the ad was posted, or {@code null} if it could not be parsed
 *                          (treated as "old" so it never counts as a fresh offer).
 * @param topAd            whether kleinanzeigen flagged this as a promoted "TOP" ad.
 */
public record KleinanzeigenListing(
        String adId,
        String title,
        String url,
        BigDecimal price,
        String priceText,
        String location,
        String postedText,
        Long postedEpochMillis,
        boolean topAd
) {

    /** Age of the listing relative to {@code now}, in seconds (never negative). */
    public long ageSeconds(Instant now) {
        if (postedEpochMillis == null) {
            return Long.MAX_VALUE;
        }
        long age = (now.toEpochMilli() - postedEpochMillis) / 1000L;
        return Math.max(0L, age);
    }
}
