package de.wss.portasplit.chains;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * toom per-branch availability via toom's public JSON API (api.toom.de - no bot protection, no auth).
 * Maps branches → market_id via {@code /public/api/markets}, then asks for all (market, product) pairs
 * in a single {@code POST /public/v1/buyboxcases}. In-stock ⇔ response {@code state == "available"}.
 */
@Component
public class ToomStockClient implements ChainStockClient {

    private static final Logger log = LoggerFactory.getLogger(ToomStockClient.class);

    private static final String MARKETS_URL = "https://api.toom.de/public/api/markets";
    private static final String BUYBOX_URL = "https://api.toom.de/public/v1/buyboxcases";
    private static final String PRODUCT_URL = "https://toom.de/p/_/%s";

    private static final Map<Product, String> SAP_IDS = Map.of(
            Product.PORTASPLIT, "10272593",
            Product.PORTASPLIT_COOL, "10515238");

    /**
     * Canonical online article pages (the real slug URLs, as still recorded in the reference data). toom's
     * short {@code /p/_/{sap}} link 301-redirects to its slug while the product is listed and answers a
     * hard 404 once it is delisted, so probing it tells us whether the article page still exists at all -
     * independently of the per-market pickup API, which keeps reporting in-store stock for a delisted item.
     */
    private static final Map<Product, String> ONLINE_URLS = Map.of(
            Product.PORTASPLIT, "https://toom.de/p/mobiles-klimageraet-portasplit-12000-btuh/9350668",
            Product.PORTASPLIT_COOL, "https://toom.de/p/split-klimaanlage-portasplit-cool-8000btuh/10515238");

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final AppProperties props;
    private final JobLogger jobLog;

    public ToomStockClient(RestClient restClient, ObjectMapper mapper, AppProperties props, JobLogger jobLog) {
        this.restClient = restClient;
        this.mapper = mapper;
        this.props = props;
        this.jobLog = jobLog;
    }

    @Override
    public JobType jobType() {
        return JobType.TOOM;
    }

    @Override
    public ShopSource source() {
        return ShopSource.TOOM;
    }

    @Override
    public AppProperties.Chain config() {
        return props.toom();
    }

    @Override
    public List<ChainReading> check(List<Shop> branches, List<String> errors) {
        List<ChainReading> readings = new ArrayList<>();

        Map<String, String> plzToMarket;
        try {
            plzToMarket = loadMarketsByPlz();
        } catch (Exception e) {
            errors.add("Marktliste nicht ladbar: " + e.getMessage());
            return readings;
        }

        // Map each branch to its market_id (by PLZ) and build the batch request. market_id must be a
        // JSON number (a quoted string yields HTTP 400); sap_id must be a string.
        Map<String, Shop> marketToShop = new HashMap<>();
        List<Map<String, Object>> pairs = new ArrayList<>();
        for (Shop branch : branches) {
            String marketId = branch.getPlz() == null ? null : plzToMarket.get(branch.getPlz().trim());
            if (marketId == null) {
                errors.add(branch.getName() + ": kein toom-Markt zur PLZ " + branch.getPlz());
                continue;
            }
            marketToShop.put(marketId, branch);
            for (String sap : SAP_IDS.values()) {
                pairs.add(Map.of("market_id", Integer.parseInt(marketId), "sap_id", sap));
            }
        }
        if (pairs.isEmpty()) {
            return readings;
        }

        JsonNode result;
        try {
            String body = mapper.writeValueAsString(pairs);
            result = mapper.readTree(ChainHttp.postJson(restClient, BUYBOX_URL, body));
        } catch (Exception e) {
            errors.add("buyboxcases fehlgeschlagen: " + e.getMessage());
            return readings;
        }

        // Index response by market_id + sap_id → state.
        Map<String, String> stateByKey = new HashMap<>();
        if (result.isArray()) {
            for (JsonNode n : result) {
                stateByKey.put(n.path("market_id").asText() + "|" + n.path("sap_id").asText(),
                        n.path("state").asText(""));
            }
        }

        for (Map.Entry<String, Shop> me : marketToShop.entrySet()) {
            String marketId = me.getKey();
            Shop branch = me.getValue();
            for (Map.Entry<Product, String> pe : SAP_IDS.entrySet()) {
                String state = stateByKey.get(marketId + "|" + pe.getValue());
                String url = String.format(PRODUCT_URL, pe.getValue());
                if (state == null) {
                    readings.add(new ChainReading(branch.getId(), pe.getKey(), AvailabilitySnapshot.notObserved()));
                    continue;
                }
                boolean available = state.equalsIgnoreCase("available");
                readings.add(new ChainReading(branch.getId(), pe.getKey(), new AvailabilitySnapshot(
                        true, true, available, null, null, url, System.currentTimeMillis(),
                        available ? "im Markt verfügbar" : state)));
            }
        }
        jobLog.debug("toom · {} Märkte, {} Paare per buyboxcases geprüft", marketToShop.size(), pairs.size());
        return readings;
    }

    /**
     * toom's own online shop has no scrapable <em>availability</em> signal (the SPA PDP carries no
     * schema.org {@code Offer}, and the public buyboxcases API reports only per-market pickup stock). It
     * does, however, let us verify the <em>article page still exists</em>: we probe the product's canonical
     * URL and, on a hard 404/410, report it as delisted ("online nicht gelistet") so the dashboard raises
     * the "Artikelseite gibt es nicht mehr" warning even while the pickup API still shows in-store stock.
     * Otherwise (page reachable, or the probe was inconclusive) we keep the last known state (not-observed).
     */
    @Override
    public List<ChainReading> checkOnline(Shop onlineShop, List<String> errors) {
        List<ChainReading> readings = new ArrayList<>();
        for (Product product : Product.values()) {
            String url = ONLINE_URLS.get(product);
            int status = -1;
            if (url != null) {
                try {
                    status = ChainHttp.statusOf(restClient, url);
                } catch (Exception e) {
                    log.debug("toom article-page probe failed for {}: {}", product, e.getMessage());
                    errors.add(onlineShop.getName() + " / " + product.displayName() + ": " + e.getMessage());
                }
            }
            if (ChainJsonLd.isGoneStatus(status)) {
                jobLog.info("toom: Artikelseite für {} nicht mehr erreichbar (HTTP {})", product.displayName(), status);
                readings.add(new ChainReading(onlineShop.getId(), product, new AvailabilitySnapshot(
                        true, true, false, null, null, url, System.currentTimeMillis(),
                        "online " + ChainJsonLd.NOT_LISTED_MARK)));
            } else {
                readings.add(new ChainReading(onlineShop.getId(), product, AvailabilitySnapshot.notObserved()));
            }
        }
        return readings;
    }

    private Map<String, String> loadMarketsByPlz() throws Exception {
        JsonNode markets = mapper.readTree(ChainHttp.get(restClient, MARKETS_URL)).path("markets");
        Map<String, String> map = new HashMap<>();
        if (markets.isArray()) {
            for (JsonNode m : markets) {
                String zip = m.path("address").path("zip").asText("");
                String id = m.path("id").asText("");
                if (!zip.isBlank() && !id.isBlank()) {
                    map.putIfAbsent(zip, id);
                }
            }
        }
        log.debug("toom markets loaded: {}", map.size());
        return map;
    }
}
