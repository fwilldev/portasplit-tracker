package de.wss.portasplit.service;

import de.wss.portasplit.domain.TelegramSubscriber;
import de.wss.portasplit.domain.TelegramSubscriber.Source;
import de.wss.portasplit.domain.TelegramSubscriber.State;
import de.wss.portasplit.repository.TelegramSubscriberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Opt-in lifecycle and the "env is only a seed" merge semantics. */
class TelegramSubscriberServiceTest {

    private Map<String, TelegramSubscriber> store;
    private TelegramSubscriberService service;

    @BeforeEach
    void setUp() {
        store = new LinkedHashMap<>();
        TelegramSubscriberRepository repo = mock(TelegramSubscriberRepository.class);
        when(repo.existsById(any())).thenAnswer(i -> store.containsKey(i.getArgument(0)));
        when(repo.findById(any())).thenAnswer(i -> Optional.ofNullable(store.get(i.getArgument(0))));
        when(repo.save(any())).thenAnswer(i -> {
            TelegramSubscriber s = i.getArgument(0);
            store.put(s.getChatId(), s);
            return s;
        });
        when(repo.findByState(any())).thenAnswer(i -> store.values().stream()
                .filter(s -> s.getState() == i.<State>getArgument(0)).toList());
        when(repo.countByState(any())).thenAnswer(i -> store.values().stream()
                .filter(s -> s.getState() == i.<State>getArgument(0)).count());
        when(repo.findByStateNotOrderByCreatedAtAsc(any())).thenAnswer(i -> store.values().stream()
                .filter(s -> s.getState() != i.<State>getArgument(0))
                .sorted(Comparator.comparing(TelegramSubscriber::getCreatedAt))
                .toList());
        service = new TelegramSubscriberService(repo);
    }

    @Test
    void startCreatesPendingAndIsNotYetARecipient() {
        service.onStart("111", "Alice", "alice");
        assertEquals(State.PENDING, store.get("111").getState());
        assertTrue(service.confirmedChatIds().isEmpty());
        assertEquals(0, service.confirmedCount());
    }

    @Test
    void confirmMakesRecipientAndIsIdempotent() {
        service.onStart("111", "Alice", "alice");
        assertTrue(service.confirm("111", "Alice", "alice"));   // newly confirmed
        assertFalse(service.confirm("111", "Alice", "alice"));  // already confirmed
        assertEquals(List.of("111"), service.confirmedChatIds());
        assertEquals(1, service.confirmedCount());
    }

    @Test
    void unsubscribeStopsMessagesButKeepsTombstone() {
        service.confirm("111", "Alice", "alice");
        service.unsubscribe("111");
        assertEquals(State.UNSUBSCRIBED, store.get("111").getState());
        assertTrue(service.confirmedChatIds().isEmpty());
    }

    @Test
    void mergeSeedAddsUnknownIdsAsConfirmedEnv() {
        service.mergeSeed(List.of("111", "222"));
        assertEquals(List.of("111", "222"), service.confirmedChatIds());
        assertEquals(Source.ENV, store.get("111").getSource());
        assertEquals(State.CONFIRMED, store.get("111").getState());
    }

    @Test
    void mergeSeedDoesNotResurrectAnOptedOutRecipient() {
        service.confirm("111", "Alice", "alice");
        service.unsubscribe("111");          // user opted out
        service.mergeSeed(List.of("111"));   // restart re-runs the env merge
        assertEquals(State.UNSUBSCRIBED, store.get("111").getState());
        assertTrue(service.confirmedChatIds().isEmpty());
    }

    @Test
    void mergeSeedLeavesAPendingOptInUntouched() {
        service.onStart("111", "Bob", null);   // pending, not yet confirmed
        service.mergeSeed(List.of("111"));      // must not auto-confirm via the env merge
        assertEquals(State.PENDING, store.get("111").getState());
    }

    @Test
    void restartMergeAfterOptOutOfADifferentChannelStillSeedsOthers() {
        service.confirm("111", "Alice", "alice");
        service.unsubscribe("111");
        service.mergeSeed(List.of("111", "222"));
        assertEquals(List.of("222"), service.confirmedChatIds());  // 111 stays out, 222 seeded
    }
}
