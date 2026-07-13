package de.wss.portasplit.web.dto;

import java.util.List;

/** A shop plus the status of each tracked product, for the dashboard table. */
public record ShopOverviewDto(
        Long id,
        String chain,
        String name,
        String city,
        String plz,
        String street,
        boolean onlineOnly,
        String source,
        boolean enabled,
        boolean anyAvailable,
        /** Distance (km) from the configured radius centre; null if no centre is set or no coordinates. */
        Double distanceKm,
        /** Branch coordinates for the map; null for online shops or branches without geocoding. */
        Double lat,
        Double lon,
        List<ProductStatusDto> products
) {
}
