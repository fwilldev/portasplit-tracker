package de.wss.portasplit.service;

import de.wss.portasplit.domain.TelegramSubscriber;
import de.wss.portasplit.domain.TelegramSubscriber.Source;
import de.wss.portasplit.domain.TelegramSubscriber.State;
import de.wss.portasplit.repository.TelegramSubscriberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Runtime-managed Telegram recipients, backed by the database. The env config
 * ({@code TELEGRAM_CHAT_ID}) is only an initial seed that is merged in on startup; people opt in (and
 * out) at runtime through the bot ({@code /start} + confirm, {@code /stop}). Only
 * {@link State#CONFIRMED} subscribers are messaged.
 */
@Service
public class TelegramSubscriberService {

    private static final Logger log = LoggerFactory.getLogger(TelegramSubscriberService.class);

    private final TelegramSubscriberRepository repo;

    public TelegramSubscriberService(TelegramSubscriberRepository repo) {
        this.repo = repo;
    }

    /** Chat ids that currently receive notifications. */
    @Transactional(readOnly = true)
    public List<String> confirmedChatIds() {
        return repo.findByState(State.CONFIRMED).stream()
                .map(TelegramSubscriber::getChatId)
                .toList();
    }

    /** Number of confirmed recipients. */
    @Transactional(readOnly = true)
    public long confirmedCount() {
        return repo.countByState(State.CONFIRMED);
    }

    /** All subscribers except opted-out tombstones, oldest first (for the settings UI). */
    @Transactional(readOnly = true)
    public List<TelegramSubscriber> activeSubscribers() {
        return repo.findByStateNotOrderByCreatedAtAsc(State.UNSUBSCRIBED);
    }

    /**
     * Merges the env-seeded chat ids into the table on startup: a seed id is inserted as a confirmed
     * ENV subscriber only when no row for it exists yet. Existing rows (in any state) are left
     * untouched, so a later {@code /stop} is not silently undone on the next restart.
     */
    @Transactional
    public void mergeSeed(Collection<String> seedChatIds) {
        int added = 0;
        for (String chatId : seedChatIds) {
            if (repo.existsById(chatId)) {
                continue;
            }
            TelegramSubscriber s = new TelegramSubscriber(chatId);
            s.setState(State.CONFIRMED);
            s.setSource(Source.ENV);
            touch(s);
            repo.save(s);
            added++;
        }
        if (added > 0) {
            log.info("Seeded {} Telegram recipient(s) from env config", added);
        }
    }

    /**
     * Records a {@code /start}: creates a PENDING subscriber when unknown, or moves an opted-out one
     * back to PENDING. A confirmed subscriber is left confirmed (its profile is refreshed). Returns the
     * resulting state so the caller can tailor its reply.
     */
    @Transactional
    public State onStart(String chatId, String firstName, String username) {
        TelegramSubscriber s = repo.findById(chatId).orElseGet(() -> new TelegramSubscriber(chatId));
        s.setFirstName(firstName);
        s.setUsername(username);
        if (s.getState() == State.UNSUBSCRIBED || s.getState() == null) {
            s.setState(State.PENDING);
        }
        touch(s);
        repo.save(s);
        return s.getState();
    }

    /**
     * Confirms an opt-in (button pressed). Creates the row if it somehow does not exist yet. Returns
     * {@code true} when this call actually flipped the subscriber to confirmed (i.e. it was not already
     * confirmed), so the caller can decide whether to send the welcome message.
     */
    @Transactional
    public boolean confirm(String chatId, String firstName, String username) {
        TelegramSubscriber s = repo.findById(chatId).orElseGet(() -> {
            TelegramSubscriber n = new TelegramSubscriber(chatId);
            n.setSource(Source.BOT);
            return n;
        });
        if (firstName != null) {
            s.setFirstName(firstName);
        }
        if (username != null) {
            s.setUsername(username);
        }
        boolean wasConfirmed = s.getState() == State.CONFIRMED;
        s.setState(State.CONFIRMED);
        touch(s);
        repo.save(s);
        return !wasConfirmed;
    }

    /** Opts a chat out ({@code /stop} or UI removal). Kept as a tombstone. No-op when unknown. */
    @Transactional
    public void unsubscribe(String chatId) {
        repo.findById(chatId).ifPresent(s -> {
            s.setState(State.UNSUBSCRIBED);
            touch(s);
            repo.save(s);
        });
    }

    private static void touch(TelegramSubscriber s) {
        s.setUpdatedAt(Instant.now());
    }
}
