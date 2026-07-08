package de.wss.portasplit.service;

import de.wss.portasplit.chains.ToomBecameAvailableEvent;
import de.wss.portasplit.chains.ToomBecameUnavailableEvent;
import de.wss.portasplit.config.AppProperties;
import de.wss.portasplit.domain.AvailabilityEvent;
import de.wss.portasplit.domain.Product;
import de.wss.portasplit.domain.ProductAvailability;
import de.wss.portasplit.domain.Shop;
import de.wss.portasplit.domain.ShopSource;
import de.wss.portasplit.repository.AvailabilityEventRepository;
import de.wss.portasplit.repository.ProductAvailabilityRepository;
import de.wss.portasplit.repository.ShopRepository;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Source-neutral reconciliation of one (shop, product) availability reading: updates the current
 * state, appends a change event to the history and fires a notification on the
 * "unavailable → available" transition. Shared by all availability checkers.
 */
@Service
public class AvailabilityReconciler {

    private final ProductAvailabilityRepository availabilityRepository;
    private final AvailabilityEventRepository eventRepository;
    private final ShopRepository shopRepository;
    private final NotificationService notificationService;
    private final RadiusService radiusService;
    private final AppProperties props;
    private final ApplicationEventPublisher events;
    private final SettingsService settings;

    public AvailabilityReconciler(ProductAvailabilityRepository availabilityRepository,
                                  AvailabilityEventRepository eventRepository,
                                  ShopRepository shopRepository,
                                  NotificationService notificationService,
                                  RadiusService radiusService,
                                  AppProperties props,
                                  ApplicationEventPublisher events,
                                  SettingsService settings) {
        this.availabilityRepository = availabilityRepository;
        this.eventRepository = eventRepository;
        this.shopRepository = shopRepository;
        this.notificationService = notificationService;
        this.radiusService = radiusService;
        this.props = props;
        this.events = events;
        this.settings = settings;
    }

    /**
     * Reconciles a single (shop, product) in its own transaction, loading the shop fresh by id.
     * Used by the Amazon checker, where each product is scraped outside any transaction and
     * persisted independently.
     */
    @Transactional
    public boolean reconcileInTx(Long shopId, Product product, AvailabilitySnapshot snap, Instant now) {
        Shop shop = shopRepository.findById(shopId).orElse(null);
        if (shop == null) {
            return false;
        }
        return reconcile(shop, product, snap, now);
    }

    /** Updates state for one (shop, product); returns whether it is currently available. */
    public boolean reconcile(Shop shop, Product product, AvailabilitySnapshot snap, Instant now) {
        ProductAvailability state = availabilityRepository
                .findByShopIdAndProduct(shop.getId(), product)
                .orElse(null);

        // A run that did not produce a definitive reading must not flip a known state to
        // "unavailable" or create history for it; just record that we tried (if state exists).
        if (!snap.observed()) {
            if (state != null) {
                state.setLastCheckedAt(now);
                availabilityRepository.save(state);
                return state.isAvailable();
            }
            return false;
        }

        boolean isNew = state == null;
        if (isNew) {
            state = new ProductAvailability(shop, product);
            state.setFirstSeenAt(now);
        }

        Integer prevStock = state.getCurrentStock();
        boolean prevAvailable = state.isAvailable();
        // Captured before the reserve-issue note is (possibly) overwritten below, so we can detect the
        // transition into the "pickup stock, but only reservable by phone/in person" state.
        String prevReserveIssueNote = state.getReserveIssueNote();
        boolean prevCallOnly = isCallOnly(prevAvailable, prevReserveIssueNote, prevStock);

        Integer newStock = snap.stock();
        boolean newAvailable = snap.available();

        // Only refresh url/price/note/source when the article was actually present this run,
        // so a temporarily-missing store keeps its last known link/price.
        if (snap.refreshMeta()) {
            state.setUrl(snap.url());
            state.setSourceTimestamp(snap.sourceTimestamp());
            state.setNote(snap.note());
            if (snap.price() != null) {
                state.setPrice(snap.price());
            }
        }
        state.setCurrentStock(newStock);
        state.setAvailable(newAvailable);
        state.setLastCheckedAt(now);
        // We got a definitive reading this run: mark it observed. This stays in lock-step with
        // lastCheckedAt on every successful run; only a failed (not-observed) run lets lastCheckedAt
        // pull ahead, which the dashboard reads as "this article can no longer be updated".
        state.setLastObservedAt(now);
        if (newAvailable) {
            state.setLastAvailableAt(now);
        }
        // Sources that check reservability each run (e.g. Bauhaus, whose per-branch stock API reports
        // pickup stock but not whether it can actually be reserved) set/clear the reserve-issue note
        // here. Sources that don't (managesReserveIssue=false) leave it as-is, so toom's out-of-band
        // note (set by ToomReserveVerifier) survives the poll.
        if (snap.managesReserveIssue()) {
            state.setReserveIssueNote(snap.reserveIssueNote());
        }

        BigDecimal price = state.getPrice();
        boolean changed = !Objects.equals(prevStock, newStock) || prevAvailable != newAvailable;

        if (isNew) {
            state.setLastChangedAt(now);
            availabilityRepository.save(state);
            recordEvent(shop, product, newAvailable, newStock, price, "INITIAL", now);
            // The Umkreissuche gates alerts: branches outside the radius are still tracked, but only
            // in-scope branches (and all online shops) fire a Telegram notification.
            if (newAvailable && props.notifications().notifyOnFirstSeen() && radiusService.inScope(shop)) {
                fireAvailabilityAlert(shop, product, newStock, price, state);
            }
        } else if (changed) {
            state.setLastChangedAt(now);
            availabilityRepository.save(state);
            String type = eventType(prevAvailable, newAvailable);
            recordEvent(shop, product, newAvailable, newStock, price, type, now);
            if (!prevAvailable && newAvailable && radiusService.inScope(shop)) {
                fireAvailabilityAlert(shop, product, newStock, price, state);
            } else if (prevAvailable && !newAvailable
                    && shop.getSource() == ShopSource.TOOM && !shop.isOnlineOnly()) {
                // Reset the toom reserve-attempt counter for this branch/product when it sells out,
                // so the next genuine restock gets a fresh allowance of attempts.
                events.publishEvent(new ToomBecameUnavailableEvent(shop.getId(), product, shop.getPlz()));
            }
        } else {
            availabilityRepository.save(state);
        }

        // Independent of the available→available path above: a branch that just showed pickup stock it
        // can't reserve online (available stays false) fires an opt-in "call the store" alert on the
        // edge into that state, gated by the same Umkreissuche radius. Only on the transition, so it
        // does not re-fire every poll while the article sits in the call-only state.
        boolean newCallOnly = isCallOnly(newAvailable, state.getReserveIssueNote(), newStock);
        if (!prevCallOnly && newCallOnly
                && settings.callOnlyNotifyEnabled() && radiusService.inScope(shop)) {
            notificationService.notifyCallOnly(
                    shop, product, newStock, price, state.getUrl(), state.getReserveIssueNote());
        }

        return newAvailable;
    }

    /**
     * A (shop, product) is "call-only" when it has pickup stock but is not reservable online: the
     * store carries it, yet the only way to secure it is by phone/in person. Mirrors the dashboard's
     * amber "Verfügbar, nicht reservierbar" badge (see {@code ShopTable.jsx}).
     */
    private static boolean isCallOnly(boolean available, String reserveIssueNote, Integer stock) {
        return !available && reserveIssueNote != null && stock != null && stock > 0;
    }

    /**
     * Fires the availability alert for an in-scope unavailable→available transition. For a toom
     * <em>branch</em> (market-pickup) with the reserve-verification feature on, this publishes a
     * {@link ToomBecameAvailableEvent} so {@link de.wss.portasplit.chains.ToomReserveVerifier} can
     * confirm the article is genuinely reservable before alerting (toom's buybox signal over-reports);
     * for every other shop — and for toom when the feature is off — it sends the plain notification.
     */
    private void fireAvailabilityAlert(Shop shop, Product product, Integer newStock, BigDecimal price,
                                       ProductAvailability state) {
        if (shop.getSource() == ShopSource.TOOM && !shop.isOnlineOnly() && settings.toomAutoReserveEnabled()) {
            events.publishEvent(new ToomBecameAvailableEvent(
                    shop.getId(), product, shop.getPlz(), shop.getName(), state.getUrl()));
        } else {
            notificationService.notifyAvailable(shop, product, newStock, price, state.getUrl(), state.getNote());
        }
    }

    private static String eventType(boolean prevAvailable, boolean newAvailable) {
        if (!prevAvailable && newAvailable) {
            return "AVAILABLE";
        }
        if (prevAvailable && !newAvailable) {
            return "UNAVAILABLE";
        }
        return "STOCK_CHANGE";
    }

    private void recordEvent(Shop shop, Product product, boolean available, Integer stock,
                             BigDecimal price, String type, Instant now) {
        eventRepository.save(new AvailabilityEvent(shop, product, available, stock, price, type, now));
    }
}
