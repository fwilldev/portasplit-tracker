package de.wss.portasplit.service;

import de.wss.portasplit.config.AppProperties;
import de.wss.portasplit.domain.Product;
import de.wss.portasplit.domain.ProductAvailability;
import de.wss.portasplit.domain.Shop;
import de.wss.portasplit.repository.AvailabilityEventRepository;
import de.wss.portasplit.repository.ProductAvailabilityRepository;
import de.wss.portasplit.repository.ShopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The opt-in "call-only" alert path: a branch that shows pickup stock it cannot reserve online
 * (available=false, reserve-issue note set, stock &gt; 0) fires {@code notifyCallOnly} — but only on
 * the transition into that state, only when the feature is enabled, and only inside the radius.
 */
class AvailabilityReconcilerCallOnlyTest {

    private static final Product PRODUCT = Product.PORTASPLIT;
    private static final String NOTE = "Verfügbar, aber nicht reservierbar";

    private ProductAvailabilityRepository availabilityRepository;
    private AvailabilityEventRepository eventRepository;
    private ShopRepository shopRepository;
    private NotificationService notifications;
    private RadiusService radiusService;
    private AppProperties props;
    private ApplicationEventPublisher events;
    private SettingsService settings;
    private AvailabilityReconciler reconciler;

    private Shop shop;

    @BeforeEach
    void setUp() {
        availabilityRepository = mock(ProductAvailabilityRepository.class);
        eventRepository = mock(AvailabilityEventRepository.class);
        shopRepository = mock(ShopRepository.class);
        notifications = mock(NotificationService.class);
        radiusService = mock(RadiusService.class);
        props = mock(AppProperties.class);
        events = mock(ApplicationEventPublisher.class);
        settings = mock(SettingsService.class);
        reconciler = new AvailabilityReconciler(availabilityRepository, eventRepository, shopRepository,
                notifications, radiusService, props, events, settings);

        shop = mock(Shop.class);
        when(shop.getId()).thenReturn(1L);

        // Persist-through: save() returns the same instance and subsequent finds see it, so a second
        // poll observes the state left by the first (exercising the "already call-only" case).
        AtomicReference<ProductAvailability> stored = new AtomicReference<>();
        when(availabilityRepository.save(any())).thenAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(availabilityRepository.findByShopIdAndProduct(eq(1L), eq(PRODUCT)))
                .thenAnswer(inv -> Optional.ofNullable(stored.get()));
    }

    /** available=false + note + stock>0 with the feature on and in scope → one call-only alert. */
    private AvailabilitySnapshot callOnlySnap() {
        return new AvailabilitySnapshot(true, true, false, 3, null, "http://x", 1L, "3 Stück im Markt", true, NOTE);
    }

    @Test
    void firesCallOnlyAlertOnTransitionWhenEnabledAndInScope() {
        when(settings.callOnlyNotifyEnabled()).thenReturn(true);
        when(radiusService.inScope(shop)).thenReturn(true);

        reconciler.reconcile(shop, PRODUCT, callOnlySnap(), Instant.now());

        verify(notifications, times(1)).notifyCallOnly(eq(shop), eq(PRODUCT), eq(3), any(), eq("http://x"), eq(NOTE));
        verify(notifications, never()).notifyAvailable(any(), any(), any(), any(), any(), any());
    }

    @Test
    void doesNotFireWhenFeatureDisabled() {
        when(settings.callOnlyNotifyEnabled()).thenReturn(false);
        when(radiusService.inScope(shop)).thenReturn(true);

        reconciler.reconcile(shop, PRODUCT, callOnlySnap(), Instant.now());

        verify(notifications, never()).notifyCallOnly(any(), any(), any(), any(), any(), any());
    }

    @Test
    void doesNotFireWhenOutOfScope() {
        when(settings.callOnlyNotifyEnabled()).thenReturn(true);
        when(radiusService.inScope(shop)).thenReturn(false);

        reconciler.reconcile(shop, PRODUCT, callOnlySnap(), Instant.now());

        verify(notifications, never()).notifyCallOnly(any(), any(), any(), any(), any(), any());
    }

    @Test
    void doesNotReFireWhileStayingCallOnly() {
        when(settings.callOnlyNotifyEnabled()).thenReturn(true);
        when(radiusService.inScope(shop)).thenReturn(true);

        Instant now = Instant.now();
        reconciler.reconcile(shop, PRODUCT, callOnlySnap(), now);
        // Same call-only reading on the next poll: still not reservable, no new alert.
        reconciler.reconcile(shop, PRODUCT, callOnlySnap(), now);

        verify(notifications, times(1)).notifyCallOnly(any(), any(), any(), any(), any(), any());
    }
}
