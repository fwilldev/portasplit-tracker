package de.wss.portasplit.repository;

import de.wss.portasplit.domain.TelegramSubscriber;
import de.wss.portasplit.domain.TelegramSubscriber.State;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TelegramSubscriberRepository extends JpaRepository<TelegramSubscriber, String> {

    List<TelegramSubscriber> findByState(State state);

    List<TelegramSubscriber> findByStateNotOrderByCreatedAtAsc(State state);

    long countByState(State state);
}
