package de.wss.portasplit.chains;

import de.wss.portasplit.amazon.CloakBrowserClient;
import de.wss.portasplit.config.AppProperties;
import de.wss.portasplit.domain.Product;
import de.wss.portasplit.domain.Shop;
import de.wss.portasplit.domain.ShopSource;
import de.wss.portasplit.jobs.JobLogger;
import de.wss.portasplit.jobs.JobType;
import de.wss.portasplit.service.AvailabilitySnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hornbach availability. Hornbach sits behind a Fastly bot challenge (CloakBrowser passes it) and serves
 * a React/Apollo storefront whose data is in an embedded Apollo state. The Midea PortaSplit is currently
 * <em>not listed</em> on hornbach.de (seasonal - live-confirmed "0-Treffer"), so there is no article /
 * PDP to query per market yet. This worker therefore watches the search listing: while the product is
 * unlisted it reports every branch as not available ("nicht gelistet"); once Hornbach relists it
 * ({@code articleCount > 0}) it flags that prominently so the per-market GraphQL check can be added.
 */
@Component
public class HornbachStockClient implements ChainStockClient {

    private static final int SEED = 8282;
    private static final String SEARCH_PATH = "/s/Midea%20PortaSplit";
    private static final String SEARCH_URL = "https://www.hornbach.de" + SEARCH_PATH;
    /**
     * The Midea PortaSplit's canonical Hornbach article page. Hornbach answers a <em>soft</em> 404 for a
     * delisted article (HTTP 200 with a "Seite nicht gefunden" page and no product data), so we probe this
     * page and treat a not-found render as "article page gone" - and use it as the product link shown on
     * the dashboard, instead of the generic search page.
     */
    private static final String PRODUCT_PATH = "/p/klimasplitgeraet-midea-portasplit-12-000-btu-105-m-weiss/12356554/";
    private static final String PRODUCT_URL = "https://www.hornbach.de" + PRODUCT_PATH;
    private static final Pattern ARTICLE_COUNT = Pattern.compile("\"articleCount\":\\s*(\\d+)");

    /** A single CloakBrowser search fetch is reused by check() + checkOnline() within one run. */
    private static final long LISTING_TTL_MS = 60_000;

    private final AppProperties props;
    private final CloakBrowserClient cloak;
    private final JobLogger jobLog;

    private volatile Listing cachedListing;
    private volatile long cachedListingAt;

    public HornbachStockClient(AppProperties props, CloakBrowserClient cloak, JobLogger jobLog) {
        this.props = props;
        this.cloak = cloak;
        this.jobLog = jobLog;
    }

    @Override
    public JobType jobType() {
        return JobType.HORNBACH;
    }

    @Override
    public ShopSource source() {
        return ShopSource.HORNBACH;
    }

    @Override
    public AppProperties.Chain config() {
        return props.hornbach();
    }

    @Override
    public List<ChainReading> check(List<Shop> branches, List<String> errors) {
        Listing listing = listing(errors);
        if (listing == null) {
            return List.of();
        }
        List<ChainReading> readings = new ArrayList<>();
        for (Shop branch : branches) {
            for (Product product : Product.values()) {
                readings.add(new ChainReading(branch.getId(), product, listing.toSnapshot()));
            }
        }
        return readings;
    }

    /**
     * Online shop. Hornbach has no per-article online stock to query while the product is unlisted, so
     * the online shop tracks the same listing signal as the branches (available stays false; a relisting
     * surfaces via the note/warning so a real per-market online check can be added).
     */
    @Override
    public List<ChainReading> checkOnline(Shop onlineShop, List<String> errors) {
        Listing listing = listing(errors);
        if (listing == null) {
            return List.of();
        }
        List<ChainReading> readings = new ArrayList<>();
        for (Product product : Product.values()) {
            readings.add(new ChainReading(onlineShop.getId(), product, listing.toSnapshot()));
        }
        return readings;
    }

    /** Fetches (and briefly caches) the Hornbach search-listing state; null if the page is unreachable. */
    private Listing listing(List<String> errors) {
        Listing cached = cachedListing;
        if (cached != null && System.currentTimeMillis() - cachedListingAt < LISTING_TTL_MS) {
            return cached;
        }
        // One CloakBrowser pass fetches both the search listing (article count) and the concrete PDP
        // (does the article page still exist), reusing the same cf_clearance/fingerprint.
        List<CloakBrowserClient.InPageResponse> res = cloak.behindCloudflare(SEED, SEARCH_URL,
                List.of(new CloakBrowserClient.InPageReq("GET", SEARCH_PATH, Map.of("Accept", "text/html")),
                        new CloakBrowserClient.InPageReq("GET", PRODUCT_PATH, Map.of("Accept", "text/html"))));
        String html = res.isEmpty() ? null : res.get(0).body();
        if (html == null || html.isBlank()) {
            errors.add("Hornbach: Suchseite nicht erreichbar");
            return null;
        }

        int articleCount = -1;
        Matcher m = ARTICLE_COUNT.matcher(html);
        if (m.find()) {
            articleCount = Integer.parseInt(m.group(1));
        }

        // The article page itself: Hornbach serves a soft 404 (HTTP 200 + "Seite nicht gefunden") for a
        // delisted item, so check both the status and the page body.
        CloakBrowserClient.InPageResponse pdp = res.size() > 1 ? res.get(1) : null;
        boolean pdpGone = pdp == null || ChainJsonLd.isGoneStatus(pdp.status())
                || ChainJsonLd.isGonePage(pdp.body());

        String note;
        if (pdpGone) {
            // "nicht gelistet" drives the dashboard's "Artikelseite gibt es nicht mehr" warning.
            note = "Artikelseite nicht mehr erreichbar - bei Hornbach nicht gelistet";
            if (articleCount > 0) {
                jobLog.warn("Hornbach: PortaSplit-Artikelseite ist 404, Suche zeigt aber {} Treffer - evtl. unter neuer URL relistet",
                        articleCount);
            } else {
                jobLog.info("Hornbach: Midea PortaSplit nicht mehr gelistet (Artikelseite 404)");
            }
        } else if (articleCount > 0) {
            note = "bei Hornbach wieder gelistet (" + articleCount + " Treffer) - Markt-Bestand prüfen";
            jobLog.warn("Hornbach: Midea PortaSplit ist WIEDER GELISTET ({} Treffer) - per-Markt-Worker ergänzen!",
                    articleCount);
        } else {
            note = "bei Hornbach nicht gelistet";
            jobLog.info("Hornbach: Midea PortaSplit aktuell nicht gelistet (0 Treffer)");
        }
        Listing listing = new Listing(note);
        cachedListing = listing;
        cachedListingAt = System.currentTimeMillis();
        return listing;
    }

    /** Listing watcher result. Availability stays false (listed ≠ in stock); the note carries the state. */
    private record Listing(String note) {
        AvailabilitySnapshot toSnapshot() {
            return new AvailabilitySnapshot(true, true, false, 0, null, PRODUCT_URL,
                    System.currentTimeMillis(), note);
        }
    }
}
