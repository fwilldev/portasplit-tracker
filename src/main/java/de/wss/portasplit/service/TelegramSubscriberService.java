package de.wss.portasplit.service;

import de.wss.portasplit.config.AppProperties;
import de.wss.portasplit.domain.SubscriberSource;
import de.wss.portasplit.domain.SubscriberState;
import de.wss.portasplit.domain.TelegramSubscriber;
import de.wss.portasplit.repository.TelegramSubscriberRepository;
import de.wss.portasplit.service.TelegramBotClient.IncomingCallback;
import de.wss.portasplit.service.TelegramBotClient.IncomingMessage;
import de.wss.portasplit.service.TelegramBotClient.Update;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Owns the Telegram recipient list: seeds it from config on startup and keeps it in sync with the
 * interactive opt-in/opt-out flow by short-polling {@code getUpdates}.
 *
 * <p>The database is the source of truth. A recipient becomes {@link SubscriberState#CONFIRMED} only
 * after an explicit confirmation (config-seeded ones are trusted and confirmed immediately); opting
 * out is a soft delete ({@link SubscriberState#UNSUBSCRIBED}) so a later restart's seed does not
 * resurrect someone who left.
 */
@Service
public class TelegramSubscriberService {

    private static final Logger log = LoggerFactory.getLogger(TelegramSubscriberService.class);

    private static final String START = "/start";
    private static final String STOP = "/stop";

    private static final String CONFIRM_PROMPT =
            "👋 Willkommen beim PortaSplit-Tracker!\n\n"
                    + "Tippe auf „Bestätigen“, um Verfügbarkeits-Benachrichtigungen zu erhalten. "
                    + "Zum Abmelden jederzeit /stop senden.";
    private static final String CONFIRMED_MSG =
            "✅ Angemeldet! Du bekommst ab jetzt Benachrichtigungen. Abmelden mit /stop.";
    private static final String ALREADY_MSG =
            "✅ Du bist bereits angemeldet. Abmelden mit /stop.";
    private static final String STOPPED_MSG =
            "👋 Abgemeldet. Du bekommst keine Benachrichtigungen mehr. Mit /start meldest du dich neu an.";

    private final TelegramSubscriberRepository repo;
    private final TelegramBotClient client;
    private final SettingsService settings;
    private final AppProperties props;

    public TelegramSubscriberService(TelegramSubscriberRepository repo, TelegramBotClient client,
                                     SettingsService settings, AppProperties props) {
        this.repo = repo;
        this.client = client;
        this.settings = settings;
        this.props = props;
    }

    // --- read API used by the controllers / TelegramService ------------------------------------

    /** Number of confirmed (active) recipients. */
    public long confirmedCount() {
        return repo.countByState(SubscriberState.CONFIRMED);
    }

    /** Chat ids of the confirmed recipients (the ones that actually receive notifications). */
    public List<String> confirmedChatIds() {
        return repo.findByStateOrderByCreatedAtAsc(SubscriberState.CONFIRMED).stream()
                .map(TelegramSubscriber::getChatId)
                .toList();
    }

    /** All non-tombstoned recipients (pending + confirmed) for the settings page, oldest first. */
    public List<TelegramSubscriber> activeSubscribers() {
        return repo.findByStateNotOrderByCreatedAtAsc(SubscriberState.UNSUBSCRIBED);
    }

    /** Opts a recipient out (soft delete). Idempotent; unknown ids are a no-op. */
    @Transactional
    public void unsubscribe(String chatId) {
        repo.findById(chatId).ifPresent(s -> {
            if (s.getState() != SubscriberState.UNSUBSCRIBED) {
                s.setState(SubscriberState.UNSUBSCRIBED);
                s.setUpdatedAt(Instant.now());
                repo.save(s);
                log.info("Telegram recipient {} unsubscribed", chatId);
            }
        });
    }

    // --- startup seed --------------------------------------------------------------------------

    /**
     * Merges the configured {@code app.telegram.chat-id} seed into the database as confirmed
     * recipients — but only ids that have no row yet, so a recipient who later sent {@code /stop} is
     * not resurrected on the next restart.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedFromConfig() {
        for (String chatId : props.telegram().chatIds()) {
            if (repo.existsById(chatId)) {
                continue;
            }
            TelegramSubscriber s = new TelegramSubscriber(chatId, SubscriberSource.ENV);
            s.setState(SubscriberState.CONFIRMED);
            repo.save(s);
            log.info("Seeded Telegram recipient {} from config", chatId);
        }
    }

    // --- interactive opt-in/opt-out polling ----------------------------------------------------

    /**
     * Short-polls Telegram for {@code /start}, {@code /stop} and confirm-button presses. The
     * getUpdates offset is persisted so already-processed updates are never re-handled across
     * restarts. Cadence is configurable via {@code app.telegram.poll-interval-ms}.
     */
    @Scheduled(
            fixedDelayString = "${app.telegram.poll-interval-ms:5000}",
            initialDelayString = "${app.telegram.poll-initial-delay-ms:8000}")
    public void poll() {
        if (!client.canOperate()) {
            return;
        }
        long offset = currentOffset();
        List<Update> updates = client.getUpdates(offset);
        long maxUpdateId = -1;
        for (Update u : updates) {
            try {
                handle(u);
            } catch (Exception e) {
                log.warn("Failed to handle Telegram update {}: {}", u.updateId(), e.getMessage());
            }
            maxUpdateId = Math.max(maxUpdateId, u.updateId());
        }
        if (maxUpdateId >= 0) {
            // Acknowledge everything up to and including the highest id we've seen.
            settings.set(SettingsService.TELEGRAM_UPDATES_OFFSET, String.valueOf(maxUpdateId + 1));
        }
    }

    private long currentOffset() {
        return settings.get(SettingsService.TELEGRAM_UPDATES_OFFSET)
                .map(v -> {
                    try {
                        return Long.parseLong(v.trim());
                    } catch (NumberFormatException e) {
                        return 0L;
                    }
                })
                .orElse(0L);
    }

    @Transactional
    protected void handle(Update u) {
        IncomingMessage msg = u.message();
        if (msg != null && msg.chatId() != null) {
            String text = msg.text() == null ? "" : msg.text().trim();
            if (text.equalsIgnoreCase(START) || text.toLowerCase().startsWith(START + " ")) {
                onStart(msg);
            } else if (text.equalsIgnoreCase(STOP)) {
                onStop(msg);
            }
            return;
        }
        IncomingCallback cb = u.callback();
        if (cb != null && cb.chatId() != null && TelegramBotClient.CONFIRM_CALLBACK.equals(cb.data())) {
            onConfirm(cb);
        }
    }

    private void onStart(IncomingMessage msg) {
        TelegramSubscriber s = repo.findById(msg.chatId())
                .orElseGet(() -> new TelegramSubscriber(msg.chatId(), SubscriberSource.BOT));
        if (s.getState() == SubscriberState.CONFIRMED) {
            client.sendPlain(msg.chatId(), ALREADY_MSG);
            return;
        }
        // New, pending again, or re-subscribing after a /stop: put back to pending and ask to confirm.
        s.setState(SubscriberState.PENDING);
        if (s.getDisplayName() == null && msg.senderName() != null) {
            s.setDisplayName(msg.senderName());
        }
        s.setUpdatedAt(Instant.now());
        repo.save(s);
        client.sendConfirmPrompt(msg.chatId(), CONFIRM_PROMPT);
        log.info("Telegram recipient {} started opt-in (pending confirmation)", msg.chatId());
    }

    private void onStop(IncomingMessage msg) {
        unsubscribe(msg.chatId());
        client.sendPlain(msg.chatId(), STOPPED_MSG);
    }

    private void onConfirm(IncomingCallback cb) {
        client.answerCallback(cb.id(), "Bestätigt");
        TelegramSubscriber s = repo.findById(cb.chatId())
                .orElseGet(() -> new TelegramSubscriber(cb.chatId(), SubscriberSource.BOT));
        if (s.getState() == SubscriberState.CONFIRMED) {
            return;
        }
        s.setState(SubscriberState.CONFIRMED);
        if (s.getDisplayName() == null && cb.senderName() != null) {
            s.setDisplayName(cb.senderName());
        }
        s.setUpdatedAt(Instant.now());
        repo.save(s);
        client.sendPlain(cb.chatId(), CONFIRMED_MSG);
        log.info("Telegram recipient {} confirmed opt-in", cb.chatId());
    }
}
