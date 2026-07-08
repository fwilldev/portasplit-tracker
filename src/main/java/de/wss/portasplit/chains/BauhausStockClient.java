package de.wss.portasplit.chains;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.wss.portasplit.amazon.CloakBrowserClient;
import de.wss.portasplit.config.AppProperties;
import de.wss.portasplit.domain.Product;
import de.wss.portasplit.domain.Shop;
import de.wss.portasplit.domain.ShopSource;
import de.wss.portasplit.jobs.JobLogger;
import de.wss.portasplit.jobs.JobType;
import de.wss.portasplit.service.AvailabilitySnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BAUHAUS per-branch availability. Bauhaus is behind Cloudflare bot-management (plain HTTP is 403), so
 * this drives the shared CloakBrowser to pass Cloudflare once, then calls bauhaus.info's own JSON APIs
 * with in-page fetches: {@code /api/product-availability/availability-detail/{item}} returns the stock
 * of every store within a radius (keyed by Bauhaus location id), {@code /api/store-data-compact} maps a
 * location id to its PLZ. Branches are matched to stores by PLZ; the location→PLZ map is cached.
 */
@Component
public class BauhausStockClient implements ChainStockClient {

    private static final Logger log = LoggerFactory.getLogger(BauhausStockClient.class);

    private static final int SEED = 8181;
    private static final String REF_STORE = "857";
    private static final int RADIUS_KM = 1000;
    private static final String PDP_URL = "https://www.bauhaus.info/p/31934233";
    private static final String PRODUCT_URL = "https://www.bauhaus.info/p/%s";
    private static final Map<String, String> JSON = Map.of("Accept", "application/json");

    private static final Map<Product, String> ITEMS = Map.of(
            Product.PORTASPLIT, "31934233",
            Product.PORTASPLIT_COOL, "33946696");

    private final Map<String, String> locToZip = new ConcurrentHashMap<>();

    private final AppProperties props;
    private final CloakBrowserClient cloak;
    private final ObjectMapper mapper;
    private final JobLogger jobLog;

    public BauhausStockClient(AppProperties props, CloakBrowserClient cloak, ObjectMapper mapper,
                              JobLogger jobLog) {
        this.props = props;
        this.cloak = cloak;
        this.mapper = mapper;
        this.jobLog = jobLog;
    }

    @Override
    public JobType jobType() {
        return JobType.BAUHAUS;
    }

    @Override
    public ShopSource source() {
        return ShopSource.BAUHAUS;
    }

    @Override
    public AppProperties.Chain config() {
        return props.bauhaus();
    }

    @Override
    public List<ChainReading> check(List<Shop> branches, List<String> errors) {
        List<Product> order = new ArrayList<>(ITEMS.keySet());

        List<CloakBrowserClient.InPageReq> availReqs = new ArrayList<>();
        for (Product p : order) {
            availReqs.add(new CloakBrowserClient.InPageReq("GET",
                    "/api/product-availability/availability-detail/" + ITEMS.get(p)
                            + "?storeId=" + REF_STORE + "&searchRadius=" + RADIUS_KM, JSON));
        }
        List<CloakBrowserClient.InPageResponse> availRes = cloak.behindCloudflare(SEED, PDP_URL, availReqs);

        Map<Product, Map<String, JsonNode>> availByProduct = new LinkedHashMap<>();
        Set<String> locIds = new LinkedHashSet<>();
        for (int i = 0; i < order.size(); i++) {
            Product p = order.get(i);
            Map<String, JsonNode> byLoc = new LinkedHashMap<>();
            parseAvailability(i < availRes.size() ? availRes.get(i).body() : null, byLoc, errors, p);
            availByProduct.put(p, byLoc);
            locIds.addAll(byLoc.keySet());
        }
        if (locIds.isEmpty()) {
            errors.add("Bauhaus: keine Verfügbarkeitsdaten erhalten");
            return List.of();
        }

        ensureStoreZips(locIds);
        Map<String, String> zipToLoc = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : locToZip.entrySet()) {
            zipToLoc.putIfAbsent(e.getValue(), e.getKey());
        }

        // Match the tracked branches to Bauhaus stores by PLZ, once.
        Map<Long, String> branchToLoc = new LinkedHashMap<>();
        for (Shop branch : branches) {
            String plz = branch.getPlz() == null ? null : branch.getPlz().trim();
            String locId = plz == null ? null : zipToLoc.get(plz);
            if (locId == null) {
                errors.add(branch.getName() + ": keine Bauhaus-Filiale zur PLZ " + plz);
                continue;
            }
            branchToLoc.put(branch.getId(), locId);
        }

        // The availability-detail API reports pickup stock but marks a store "AVAILABLE" even when the
        // article cannot actually be reserved (freight/Spedition items like the PortaSplit routinely
        // show "N Stück verfügbar" next to "Dieses Produkt kann derzeit nicht reserviert werden"). The
        // /api/purchasability endpoint is the ground truth, so resolve it for every in-stock
        // (product, store) and use it as the availability signal.
        Map<String, Boolean> reservable = resolveReservability(availByProduct, branchToLoc.values(), order, errors);

        List<ChainReading> readings = new ArrayList<>();
        for (Map.Entry<Long, String> be : branchToLoc.entrySet()) {
            long shopId = be.getKey();
            String locId = be.getValue();
            for (Product p : order) {
                JsonNode entry = availByProduct.get(p).get(locId);
                String url = String.format(PRODUCT_URL, ITEMS.get(p));
                if (entry == null) {
                    readings.add(new ChainReading(shopId, p, AvailabilitySnapshot.notObserved()));
                    continue;
                }
                int qty = entry.path("available_quantity").asInt(0);
                String status = entry.path("status").asText("");
                if (qty <= 0) {
                    // Definitively out of stock - nothing to reserve; clear any prior reserve-issue note.
                    readings.add(new ChainReading(shopId, p, new AvailabilitySnapshot(
                            true, true, false, 0, null, url, System.currentTimeMillis(),
                            statusText(status), true, null)));
                    continue;
                }
                Boolean canReserve = reservable.get(reserveKey(p, locId));
                if (canReserve == null) {
                    // Stock is present but reservability could not be confirmed this run: keep the last
                    // known state rather than risk a misleading "available" alert for a non-reservable item.
                    readings.add(new ChainReading(shopId, p, AvailabilitySnapshot.notObserved()));
                    continue;
                }
                String reserveIssue = canReserve ? null : "Verfügbar, aber nicht reservierbar";
                readings.add(new ChainReading(shopId, p, new AvailabilitySnapshot(
                        true, true, canReserve, qty, null, url, System.currentTimeMillis(),
                        qty + " Stück im Markt", true, reserveIssue)));
            }
        }
        jobLog.info("Bauhaus · {}/{} Filialen über {} Stores gematcht", branchToLoc.size(), branches.size(), locIds.size());
        return readings;
    }

    private static String reserveKey(Product product, String locId) {
        return product.name() + '|' + locId;
    }

    /**
     * Resolves whether each in-stock (product, store) can actually be reserved, via bauhaus.info's
     * {@code /api/purchasability} endpoint (one in-page fetch per (product, store), batched into a
     * single CloakBrowser round-trip). Returns {@code product|locId → reservable}; a missing key means
     * either the store had no stock (no check needed) or the check failed and the caller should treat
     * it as not-observed.
     */
    private Map<String, Boolean> resolveReservability(Map<Product, Map<String, JsonNode>> availByProduct,
                                                      Collection<String> locIds, List<Product> order,
                                                      List<String> errors) {
        Set<String> stores = new LinkedHashSet<>(locIds);
        List<String> keys = new ArrayList<>();
        List<CloakBrowserClient.InPageReq> reqs = new ArrayList<>();
        for (String locId : stores) {
            for (Product p : order) {
                JsonNode entry = availByProduct.get(p).get(locId);
                if (entry == null || entry.path("available_quantity").asInt(0) <= 0) {
                    continue;
                }
                keys.add(reserveKey(p, locId));
                reqs.add(new CloakBrowserClient.InPageReq("GET",
                        "/api/purchasability?productId=" + ITEMS.get(p) + "&quantity=1&storeId=" + locId, JSON));
            }
        }
        Map<String, Boolean> out = new LinkedHashMap<>();
        if (keys.isEmpty()) {
            return out;
        }
        List<CloakBrowserClient.InPageResponse> res = cloak.behindCloudflare(SEED, PDP_URL, reqs);
        for (int i = 0; i < keys.size(); i++) {
            Boolean r = parsePurchasable(mapper, i < res.size() ? res.get(i).body() : null);
            if (r != null) {
                out.put(keys.get(i), r);
            }
        }
        if (out.size() < keys.size()) {
            errors.add("Bauhaus: Reservierbarkeit für " + (keys.size() - out.size())
                    + " Markt/Artikel nicht ermittelbar");
        }
        jobLog.info("Bauhaus · Reservierbarkeit für {}/{} Markt-Artikel geprüft", out.size(), keys.size());
        return out;
    }

    /**
     * Reads the store-pickup purchasability out of a {@code /api/purchasability} response: the
     * {@code STORE}-kind result's {@code purchasable} flag (true = reservable for pickup). Returns
     * {@code null} if the response is missing/unparseable or carries no STORE result.
     */
    static Boolean parsePurchasable(ObjectMapper mapper, String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode results = mapper.readTree(body).path("results");
            if (results.isArray()) {
                for (JsonNode r : results) {
                    if ("STORE".equals(r.path("kind").asText())) {
                        return r.path("purchasable").asBoolean(false);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Bauhaus purchasability parse failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Online availability of bauhaus.info's own shop. bauhaus.info is Cloudflare-protected, so this
     * loads the PDP in the stealth context and reads the schema.org JSON-LD offer from the rendered DOM
     * (same signal as the per-branch readings, but for nationwide delivery). A failed render leaves the
     * shop's last known state untouched (not-observed) rather than flipping it to unavailable.
     */
    @Override
    public List<ChainReading> checkOnline(Shop onlineShop, List<String> errors) {
        List<ChainReading> readings = new ArrayList<>();
        for (Map.Entry<Product, String> e : ITEMS.entrySet()) {
            Product product = e.getKey();
            String item = e.getValue();
            String url = String.format(PRODUCT_URL, item);
            try {
                Map<String, Object> data = cloak.fetchFingerprinted(SEED, url, ChainJsonLd.LD_OR_GONE_JS);
                // A delisted bauhaus.info PDP renders a "…hat nicht funktioniert" page with no JSON-LD, so
                // treat the not-found flag as a definitive "article page gone" rather than a failed render.
                if (data != null && Boolean.TRUE.equals(data.get("gone"))) {
                    jobLog.info("Bauhaus: Artikelseite für {} nicht mehr erreichbar", product.displayName());
                    readings.add(new ChainReading(onlineShop.getId(), product, new AvailabilitySnapshot(
                            true, true, false, null, null, url, System.currentTimeMillis(),
                            "online " + ChainJsonLd.NOT_LISTED_MARK)));
                    continue;
                }
                List<String> blocks = jsonLdBlocks(data);
                if (blocks == null) {
                    readings.add(new ChainReading(onlineShop.getId(), product, AvailabilitySnapshot.notObserved()));
                    continue;
                }
                ChainJsonLd.Offer offer = ChainJsonLd.parseBlocks(mapper, blocks, item);
                readings.add(ChainJsonLd.onlineReading(onlineShop.getId(), product, offer, url));
            } catch (Exception ex) {
                log.debug("Bauhaus online availability failed for {}: {}", item, ex.getMessage());
                errors.add(onlineShop.getName() + " / " + product.displayName() + ": " + ex.getMessage());
                readings.add(new ChainReading(onlineShop.getId(), product, AvailabilitySnapshot.notObserved()));
            }
        }
        jobLog.info("Bauhaus · Online-Verfügbarkeit (PDP) für {} Artikel geprüft", ITEMS.size());
        return readings;
    }

    /** Pulls the JSON-LD block list out of the CloakBrowser extraction result, or null if not ready. */
    private static List<String> jsonLdBlocks(Map<String, Object> data) {
        if (data == null || !Boolean.TRUE.equals(data.get("ready")) || !(data.get("ld") instanceof List<?> raw)) {
            return null;
        }
        List<String> blocks = new ArrayList<>(raw.size());
        for (Object o : raw) {
            if (o != null) {
                blocks.add(o.toString());
            }
        }
        return blocks;
    }

    private void parseAvailability(String body, Map<String, JsonNode> byLoc, List<String> errors, Product p) {
        if (body == null || body.isBlank()) {
            errors.add("Bauhaus " + p.displayName() + ": leere Antwort");
            return;
        }
        try {
            JsonNode data = mapper.readTree(body).path("data").path("data");
            if (data.isArray()) {
                for (JsonNode n : data) {
                    String loc = n.path("location_id").asText("");
                    if (!loc.isBlank()) {
                        byLoc.put(loc, n);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Bauhaus availability parse failed for {}: {}", p, e.getMessage());
            errors.add("Bauhaus " + p.displayName() + ": Antwort nicht lesbar");
        }
    }

    private void ensureStoreZips(Set<String> locIds) {
        List<String> missing = new ArrayList<>();
        for (String loc : locIds) {
            if (!locToZip.containsKey(loc)) {
                missing.add(loc);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        List<CloakBrowserClient.InPageReq> reqs = new ArrayList<>();
        for (String loc : missing) {
            reqs.add(new CloakBrowserClient.InPageReq("GET", "/api/store-data-compact?storeId=" + loc, JSON));
        }
        List<CloakBrowserClient.InPageResponse> res = cloak.behindCloudflare(SEED, PDP_URL, reqs);
        for (int i = 0; i < missing.size() && i < res.size(); i++) {
            String body = res.get(i).body();
            if (body == null) {
                continue;
            }
            try {
                String zip = mapper.readTree(body).path("address").path("zip").asText("");
                if (!zip.isBlank()) {
                    locToZip.put(missing.get(i), zip);
                }
            } catch (Exception e) {
                log.debug("Bauhaus store-data parse failed for {}: {}", missing.get(i), e.getMessage());
            }
        }
        jobLog.info("Bauhaus · {} Store-PLZ ergänzt ({} im Cache)", missing.size(), locToZip.size());
    }

    private static String statusText(String status) {
        if (status == null || status.isBlank()) {
            return "nicht im Markt";
        }
        return switch (status) {
            case "OUT_OF_STOCK" -> "nicht im Markt";
            case "IN_STOCK" -> "im Markt";
            case "LOW_STOCK" -> "wenige im Markt";
            default -> status.replace('_', ' ').toLowerCase();
        };
    }
}
