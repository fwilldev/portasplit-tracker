package de.wss.portasplit.repository;

import de.wss.portasplit.domain.SubscriberState;
import de.wss.portasplit.domain.TelegramSubscriber;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TelegramSubscriberRepository extends JpaRepository<TelegramSubscriber, String> {

    /** Recipients in a given state, oldest first. */
    List<TelegramSubscriber> findByStateOrderByCreatedAtAsc(SubscriberState state);

    /** How many recipients are in a given state (used for the confirmed-recipient count). */
    long countByState(SubscriberState state);

    /** All non-tombstoned recipients (pending + confirmed), oldest first, for the settings page. */
    List<TelegramSubscriber> findByStateNotOrderByCreatedAtAsc(SubscriberState state);
}
