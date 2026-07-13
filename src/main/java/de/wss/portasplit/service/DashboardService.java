package de.wss.portasplit.service;

import de.wss.portasplit.config.AppProperties;
import de.wss.portasplit.domain.Product;
import de.wss.portasplit.domain.ProductAvailability;
import de.wss.portasplit.domain.Shop;
import de.wss.portasplit.domain.ShopSource;
import de.wss.portasplit.kleinanzeigen.KleinanzeigenListing;
import de.wss.portasplit.repository.AvailabilityEventRepository;
import de.wss.portasplit.repository.ProductAvailabilityRepository;
import de.wss.portasplit.repository.ShopRepository;
import de.wss.portasplit.web.dto.EventDto;
import de.wss.portasplit.web.dto.KleinanzeigenOfferDto;
import de.wss.portasplit.web.dto.KleinanzeigenStatusDto;
import de.wss.portasplit.web.dto.OverviewDto;
import de.wss.portasplit.web.dto.ProductStatusDto;
import de.wss.portasplit.web.dto.RadiusInfoDto;
import de.wss.portasplit.web.dto.ShopOverviewDto;
import de.wss.portasplit.web.dto.SummaryDto;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read-side service that assembles the dashboard payloads. */
@Service
@Transactional(readOnly = true)
public class DashboardService {

    private final ShopRepository shopRepository;
    private final ProductAvailabilityRepository availabilityRepository;
    private final AvailabilityEventRepository eventRepository;
    private final KleinanzeigenStatusHolder kleinanzeigenStatus;
    private final KleinanzeigenService kleinanzeigenService;
    private final RadiusService radiusService;
    private final AppProperties props;

    public DashboardService(ShopRepository shopRepository,
                            ProductAvailabilityRepository availabilityRepository,
                            AvailabilityEventRepository eventRepository,
                            KleinanzeigenStatusHolder kleinanzeigenStatus,
                            KleinanzeigenService kleinanzeigenService,
                            RadiusService radiusService,
                            AppProperties props) {
        this.shopRepository = shopRepository;
        this.availabilityRepository = availabilityRepository;
        this.eventRepository = eventRepository;
        this.kleinanzeigenStatus = kleinanzeigenStatus;
        this.kleinanzeigenService = kleinanzeigenService;
        this.radiusService = radiusService;
        this.props = props;
    }

    /**
     * Builds the dashboard overview. The Umkreissuche restricts which branches are returned: when the
     * radius is active only in-scope branches (within the radius) plus all online shops are included.
     * Scraping is unaffected - every branch is still polled; this only narrows the <em>display</em>.
     *
     * @param showAll when true the radius filter is bypassed (every shop is returned, still annotated
     *                with its distance) so the UI can offer an "Alle anzeigen" view.
     */
    public OverviewDto buildOverview(boolean showAll) {
        List<Shop> shops = shopRepository.findAllByOrderByChainAscNameAsc();
        RadiusService.RadiusConfig radiusCfg = radiusService.config();
        boolean filtering = radiusCfg.active() && !showAll;

        Map<Long, Map<Product, ProductAvailability>> byShop = new HashMap<>();
        for (ProductAvailability a : availabilityRepository.findAllWithShop()) {
            byShop.computeIfAbsent(a.getShop().getId(), k -> new EnumMap<>(Product.class))
                    .put(a.getProduct(), a);
        }

        // Per-branch price fallback per (source, product): toom's public API returns availability only
        // (no price), so most toom branches have no price. A chain's catalogue price is uniform across
        // its markets, so a branch that lacks one shows the most common price known for that chain.
        Map<ShopSource, Map<Product, BigDecimal>> branchFallbackPrice = computeBranchFallbackPrices(shops, byShop);

        List<ShopOverviewDto> shopDtos = new ArrayList<>(shops.size());
        long branchShops = 0;
        long onlineShops = 0;
        long enabledShops = 0;
        long branchesInRadius = 0;
        for (Shop shop : shops) {
            boolean inScope = radiusService.inScope(radiusCfg, shop);
            if (shop.isOnlineOnly()) {
                onlineShops++;
            } else {
                branchShops++;
                if (inScope) {
                    branchesInRadius++;
                }
            }
            if (shop.isEnabled()) {
                enabledShops++;
            }

            // Restrict the display to in-scope shops (online always shown), unless "Alle anzeigen".
            if (filtering && !inScope) {
                continue;
            }

            Double distanceKm = shop.isOnlineOnly()
                    ? null
                    : radiusService.distanceKm(radiusCfg, shop.getLat(), shop.getLon());

            Map<Product, ProductAvailability> states = byShop.getOrDefault(shop.getId(), Map.of());
            List<ProductStatusDto> products = new ArrayList<>(Product.values().length);
            boolean anyAvailable = false;
            for (Product product : Product.values()) {
                ProductAvailability a = states.get(product);
                if (a != null) {
                    // A chain's online shop may be priced differently from its markets, so only branches
                    // borrow the branch fallback price.
                    BigDecimal fallback = shop.isOnlineOnly() ? null
                            : branchFallbackPrice.getOrDefault(shop.getSource(), Map.of()).get(product);
                    products.add(ProductStatusDto.from(a, fallback));
                    anyAvailable = anyAvailable || a.isAvailable();
                } else {
                    products.add(ProductStatusDto.untracked(product));
                }
            }
            shopDtos.add(new ShopOverviewDto(shop.getId(), shop.getChain(), shop.getName(),
                    shop.getCity(), shop.getPlz(), shop.getStreet(), shop.isOnlineOnly(),
                    shop.getSource().name(), shop.isEnabled(), anyAvailable,
                    distanceKm == null ? null : Math.round(distanceKm * 10) / 10.0,
                    shop.isOnlineOnly() ? null : shop.getLat(),
                    shop.isOnlineOnly() ? null : shop.getLon(), products));
        }

        SummaryDto summary = new SummaryDto(shops.size(), branchShops, onlineShops,
                enabledShops, availabilityRepository.countByAvailableTrue());
        RadiusInfoDto radius = new RadiusInfoDto(radiusCfg.enabled(), radiusCfg.active(), radiusCfg.km(),
                radiusCfg.centerLat(), radiusCfg.centerLon(), radiusCfg.label(),
                branchShops, radiusCfg.active() ? branchesInRadius : branchShops);

        return new OverviewDto(summary, radius, shopDtos);
    }

    /**
     * Computes, per (source, product), the most common non-null price across a chain's <em>physical
     * branches</em>. Used to backfill branches whose per-branch check yields no price (notably toom).
     * Online shops are excluded from both the computation and the backfill, since a chain's online price
     * can differ from its market price.
     */
    private Map<ShopSource, Map<Product, BigDecimal>> computeBranchFallbackPrices(
            List<Shop> shops, Map<Long, Map<Product, ProductAvailability>> byShop) {
        Map<ShopSource, Map<Product, Map<BigDecimal, Integer>>> counts = new EnumMap<>(ShopSource.class);
        for (Shop shop : shops) {
            if (shop.isOnlineOnly()) {
                continue;
            }
            Map<Product, ProductAvailability> states = byShop.get(shop.getId());
            if (states == null) {
                continue;
            }
            for (Map.Entry<Product, ProductAvailability> e : states.entrySet()) {
                BigDecimal price = e.getValue().getPrice();
                if (price == null) {
                    continue;
                }
                counts.computeIfAbsent(shop.getSource(), k -> new EnumMap<>(Product.class))
                        .computeIfAbsent(e.getKey(), k -> new HashMap<>())
                        .merge(price, 1, Integer::sum);
            }
        }
        Map<ShopSource, Map<Product, BigDecimal>> mode = new EnumMap<>(ShopSource.class);
        counts.forEach((src, byProduct) -> byProduct.forEach((product, priceCounts) -> {
            priceCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .ifPresent(best -> mode.computeIfAbsent(src, k -> new EnumMap<>(Product.class))
                            .put(product, best.getKey()));
        }));
        return mode;
    }

    /** Builds the kleinanzeigen watcher panel: config, last-check status and the current offers. */
    public KleinanzeigenStatusDto buildKleinanzeigenStatus() {
        AppProperties.Kleinanzeigen cfg = props.kleinanzeigen();
        boolean enabled = kleinanzeigenService.enabled();
        String url = kleinanzeigenService.effectiveUrl();
        KleinanzeigenStatusHolder.Snapshot snap = kleinanzeigenStatus.last();
        if (snap == null) {
            return new KleinanzeigenStatusDto(enabled, url, cfg.freshnessMinutes(),
                    null, true, null, 0, 0, List.of());
        }

        List<KleinanzeigenOfferDto> offers = new ArrayList<>(snap.listings().size());
        int fresh = 0;
        for (KleinanzeigenListing l : snap.listings()) {
            long age = l.ageSeconds(snap.checkedAt());
            boolean isFresh = age <= snap.windowSeconds();
            if (isFresh) {
                fresh++;
            }
            String price = l.priceText() != null ? l.priceText()
                    : (l.price() != null ? l.price().toPlainString() + " €" : null);
            offers.add(new KleinanzeigenOfferDto(l.adId(), l.title(), l.url(), price, l.location(),
                    l.postedText(), age == Long.MAX_VALUE ? null : age, isFresh,
                    snap.notifiedIds().contains(l.adId()), l.topAd()));
        }
        return new KleinanzeigenStatusDto(enabled, url, cfg.freshnessMinutes(),
                snap.checkedAt(), snap.ok(), snap.error(), offers.size(), fresh, offers);
    }

    public List<EventDto> history(Long shopId, Product product) {
        return eventRepository.findByShopIdAndProductOrderByCreatedAtAsc(shopId, product)
                .stream().map(EventDto::from).toList();
    }

    public List<EventDto> recentEvents(int limit) {
        return eventRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit))
                .stream().map(EventDto::from).toList();
    }
}
