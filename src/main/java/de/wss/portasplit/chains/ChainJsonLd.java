package de.wss.portasplit.chains;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.wss.portasplit.amazon.CloakBrowserClient;
import de.wss.portasplit.domain.Product;
import de.wss.portasplit.service.AvailabilitySnapshot;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared parser for a chain's <em>online</em> availability, read from the schema.org {@code Offer}
 * embedded as JSON-LD in the product page. This is the one signal that is reliable across chains and
 * for freight ("Spedition") articles - a chain's own stock API often only reports parcel/pickup, where
 * a large item shows up as empty even while it is orderable online. Used by every per-chain
 * {@code checkOnline} (OBI/Globus via plain HTTP, Bauhaus/Hornbach via the CloakBrowser).
 */
final class ChainJsonLd {

    /** Pulls the {@code <script type="application/ld+json">…</script>} blocks out of raw HTML. */
    private static final Pattern LD_JSON = Pattern.compile(
            "<script[^>]*application/ld\\+json[^>]*>(.*?)</script>", Pattern.DOTALL);

    /**
     * CloakBrowser extraction snippet: returns {@code {ready, ld:[…]}} where {@code ld} holds the raw
     * JSON-LD blocks from the rendered DOM. {@code ready} is true only once at least one block exists,
     * so {@code fetchFingerprinted} keeps polling until the page (and its JSON-LD) has rendered.
     */
    static final String LD_EXTRACT_JS =
            "(function(){try{var n=document.querySelectorAll('script[type=\"application/ld+json\"]');"
                    + "var a=[];for(var i=0;i<n.length;i++){a.push(n[i].textContent);}"
                    + "return JSON.stringify({ready:a.length>0,ld:a});}"
                    + "catch(e){return JSON.stringify({ready:false,ld:[]});}})()";

    /**
     * Like {@link #LD_EXTRACT_JS} but also flags a "gone" (delisted / not-found) page. A delisted PDP on a
     * JS storefront (Bauhaus, Hornbach) carries <em>no</em> JSON-LD, so {@code LD_EXTRACT_JS} never turns
     * {@code ready} true and the fetch times out - indistinguishable from a transient render failure.
     * Here {@code gone} is true when the title/H1 shows a not-found page, and it also flips {@code ready}
     * so the poll returns promptly instead of waiting out the timeout. Callers treat {@code gone} as a
     * definitive "article page no longer exists" (→ {@link #NOT_LISTED_MARK}).
     */
    static final String LD_OR_GONE_JS =
            "(function(){try{var n=document.querySelectorAll('script[type=\"application/ld+json\"]');"
                    + "var a=[];for(var i=0;i<n.length;i++){a.push(n[i].textContent);}"
                    + "var t=((document.title||'')+' '+((document.querySelector('h1')||{}).textContent||'')).toLowerCase();"
                    + "var gone=/nicht gefunden|nicht funktioniert|kann nicht angezeigt werden|seite nicht/.test(t);"
                    + "return JSON.stringify({ready:a.length>0||gone,ld:a,gone:gone});}"
                    + "catch(e){return JSON.stringify({ready:false,ld:[],gone:false});}})()";

    /** Whether an HTTP status marks a gone / delisted product page (hard 404 or 410 Gone). */
    static boolean isGoneStatus(int status) {
        return status == 404 || status == 410;
    }

    /**
     * Whether a fetched HTML body is a chain's soft "not found" page rather than a real PDP. Some
     * storefronts (Hornbach) answer 200 for a delisted article but render a "Seite nicht gefunden" page
     * with no product data, so status alone is not enough.
     */
    static boolean isGonePage(String html) {
        if (html == null || html.isBlank()) {
            return false;
        }
        String lower = html.toLowerCase();
        return lower.contains("nicht gefunden") || lower.contains("nicht funktioniert")
                || lower.contains("kann nicht angezeigt werden");
    }

    /**
     * Substring shared by every "the product page loaded but carries no purchasable offer" note - i.e.
     * the article is delisted / its PDP is gone (a delisted PDP answers 404 with a page that has no
     * {@code Offer}). Chains word it slightly differently ("online nicht gelistet", "bei Hornbach nicht
     * gelistet"), but all contain this mark, so {@code ChainCheckService} can detect the delisted state
     * uniformly via {@link #isNotListed(String)} without string-matching each chain's exact wording.
     */
    static final String NOT_LISTED_MARK = "nicht gelistet";

    /** Whether a snapshot note marks a delisted / no-longer-reachable product page (see {@link #NOT_LISTED_MARK}). */
    static boolean isNotListed(String note) {
        return note != null && note.contains(NOT_LISTED_MARK);
    }

    private ChainJsonLd() {
    }

    /** One product offer: whether it is orderable online, and its price (either may be unknown). */
    record Offer(boolean available, BigDecimal price, String rawAvailability) {
    }

    /** Parses the offer out of a raw HTML page (OBI/Globus, fetched over plain HTTP). */
    static Offer parseHtml(ObjectMapper mapper, String html, String sku) {
        if (html == null || html.isBlank()) {
            return null;
        }
        List<String> blocks = new ArrayList<>();
        Matcher m = LD_JSON.matcher(html);
        while (m.find()) {
            blocks.add(m.group(1));
        }
        return parseBlocks(mapper, blocks, sku);
    }

    /**
     * Parses the offer out of already-extracted JSON-LD blocks (Bauhaus/Hornbach, read from the
     * CloakBrowser-rendered DOM via {@link #LD_EXTRACT_JS}). Prefers the {@code Product} whose
     * {@code sku} matches; otherwise falls back to the first {@code Product} offer found.
     */
    static Offer parseBlocks(ObjectMapper mapper, List<String> blocks, String sku) {
        if (blocks == null) {
            return null;
        }
        Offer fallback = null;
        for (String block : blocks) {
            if (block == null || block.isBlank()) {
                continue;
            }
            JsonNode node;
            try {
                node = mapper.readTree(block.trim());
            } catch (Exception ignore) {
                continue;
            }
            for (JsonNode product : products(node)) {
                JsonNode offerNode = firstOffer(product.path("offers"));
                if (offerNode == null) {
                    continue;
                }
                Offer offer = toOffer(offerNode);
                String foundSku = product.path("sku").asText("");
                if (sku != null && !sku.isBlank() && foundSku.equals(sku)) {
                    return offer;
                }
                if (fallback == null) {
                    fallback = offer;
                }
            }
        }
        return fallback;
    }

    /**
     * Builds the snapshot for an online shop reading. A {@code null} offer means we loaded the page but
     * found no purchasable {@code Product} (delisted / out of catalogue) → definitively not available.
     * On a failed fetch the caller should pass {@link AvailabilitySnapshot#notObserved()} instead, so a
     * transient scrape error never flips a known state.
     */
    static AvailabilitySnapshot toSnapshot(Offer offer, String url) {
        if (offer == null) {
            return new AvailabilitySnapshot(true, true, false, null, null, url,
                    System.currentTimeMillis(), "online " + NOT_LISTED_MARK);
        }
        String note = offer.available()
                ? "online lieferbar" + (offer.price() != null ? " (" + offer.price() + " €)" : "")
                : "online nicht lieferbar";
        return new AvailabilitySnapshot(true, true, offer.available(), null, offer.price(), url,
                System.currentTimeMillis(), note);
    }

    /** Convenience: a (shop, product) online reading built from a parsed offer (or {@code null}). */
    static ChainReading onlineReading(long shopId, Product product, Offer offer, String url) {
        return new ChainReading(shopId, product, toSnapshot(offer, url));
    }

    private static List<JsonNode> products(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        collectProducts(node, out);
        return out;
    }

    private static void collectProducts(JsonNode node, List<JsonNode> out) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectProducts(child, out);
            }
            return;
        }
        if (node.has("@graph")) {
            collectProducts(node.get("@graph"), out);
        }
        if (isProduct(node)) {
            out.add(node);
        }
    }

    private static boolean isProduct(JsonNode node) {
        JsonNode type = node.path("@type");
        if (type.isTextual()) {
            return "Product".equals(type.asText());
        }
        if (type.isArray()) {
            for (JsonNode t : type) {
                if ("Product".equals(t.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JsonNode firstOffer(JsonNode offers) {
        if (offers.isArray()) {
            return offers.size() > 0 ? offers.get(0) : null;
        }
        if (offers.isObject()) {
            return offers;
        }
        return null;
    }

    private static Offer toOffer(JsonNode offer) {
        String availability = offer.path("availability").asText("");
        // schema.org: InStock / LimitedAvailability = orderable online; OutOfStock / SoldOut /
        // Discontinued / InStoreOnly (note: does not contain "InStock") = not available online.
        boolean available = availability.contains("InStock") || availability.contains("LimitedAvailability");
        BigDecimal price = offer.has("price") && !offer.get("price").isNull()
                ? CloakBrowserClient.parsePrice(offer.get("price").asText()) : null;
        return new Offer(available, price, availability);
    }
}
