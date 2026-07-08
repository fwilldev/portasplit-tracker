package de.wss.portasplit.chains;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * Small helpers for the RestClient-based chain checkers: browser-like headers, absolute URLs, and
 * manual gzip handling (some chain APIs - e.g. api.toom.de - always gzip, and the JDK HttpClient
 * behind RestClient does not transparently decode it).
 */
final class ChainHttp {

    private ChainHttp() {
    }

    static String get(RestClient rc, String url) {
        byte[] body = rc.get()
                .uri(URI.create(url))
                .header("User-Agent", ChainStockClient.BROWSER_UA)
                .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.5")
                .header("Accept-Encoding", "gzip")
                .retrieve()
                .body(byte[].class);
        return decode(body);
    }

    /**
     * GETs an HTML page, manually following up to 5 redirects. The JDK HttpClient behind RestClient
     * does not auto-follow, and chain PDPs use short {@code /p/{id}/} URLs that 301 to a slug URL.
     * Non-2xx responses are returned as-is (not thrown): a delisted PDP answers 404 with an HTML page
     * that simply has no product offer, which the JSON-LD parser reads as "not listed".
     */
    static String getHtml(RestClient rc, String url) {
        String current = url;
        for (int hop = 0; hop < 5; hop++) {
            ResponseEntity<byte[]> resp = rc.get()
                    .uri(URI.create(current))
                    .header("User-Agent", ChainStockClient.BROWSER_UA)
                    .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.5")
                    .header("Accept-Encoding", "gzip")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            (request, response) -> { })
                    .toEntity(byte[].class);
            if (resp.getStatusCode().is3xxRedirection()) {
                URI location = resp.getHeaders().getLocation();
                if (location == null) {
                    break;
                }
                current = location.isAbsolute() ? location.toString()
                        : URI.create(current).resolve(location).toString();
                continue;
            }
            return decode(resp.getBody());
        }
        throw new IllegalStateException("Zu viele Redirects für " + url);
    }

    /**
     * GETs {@code url} following up to 5 redirects and returns the final HTTP status, without throwing on
     * 4xx/5xx and without keeping the body. Used to probe whether a product page still exists: a delisted
     * PDP answers 404/410 (or a live short link redirects to its slug and answers 200). Returns {@code -1}
     * if the status could not be determined (too many redirects / network error surfaces as an exception).
     */
    static int statusOf(RestClient rc, String url) {
        String current = url;
        for (int hop = 0; hop < 5; hop++) {
            ResponseEntity<Void> resp = rc.get()
                    .uri(URI.create(current))
                    .header("User-Agent", ChainStockClient.BROWSER_UA)
                    .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.5")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .retrieve()
                    .onStatus(status -> true, (request, response) -> { })
                    .toBodilessEntity();
            if (resp.getStatusCode().is3xxRedirection()) {
                URI location = resp.getHeaders().getLocation();
                if (location == null) {
                    break;
                }
                current = location.isAbsolute() ? location.toString()
                        : URI.create(current).resolve(location).toString();
                continue;
            }
            return resp.getStatusCode().value();
        }
        return -1;
    }

    static String postJson(RestClient rc, String url, String jsonBody) {
        byte[] body = rc.post()
                .uri(URI.create(url))
                .header("User-Agent", ChainStockClient.BROWSER_UA)
                .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.5")
                .header("Accept-Encoding", "gzip")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(jsonBody)
                .retrieve()
                .body(byte[].class);
        return decode(body);
    }

    private static String decode(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        if (body.length >= 2 && (body[0] & 0xFF) == 0x1F && (body[1] & 0xFF) == 0x8B) {
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(body))) {
                return new String(gz.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new String(body, StandardCharsets.UTF_8);
    }
}
