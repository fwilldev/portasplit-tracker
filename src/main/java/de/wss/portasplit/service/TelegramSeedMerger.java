package de.wss.portasplit.service;

import de.wss.portasplit.config.AppProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds the {@code TELEGRAM_CHAT_ID} env recipients into the subscriber table on startup. This is a
 * one-way merge: env ids are added only when unknown, so the database is the source of truth at
 * runtime and self-service opt-outs survive restarts.
 */
@Component
public class TelegramSeedMerger implements ApplicationRunner {

    private final AppProperties props;
    private final TelegramSubscriberService subscribers;

    public TelegramSeedMerger(AppProperties props, TelegramSubscriberService subscribers) {
        this.props = props;
        this.subscribers = subscribers;
    }

    @Override
    public void run(ApplicationArguments args) {
        subscribers.mergeSeed(props.telegram().chatIds());
    }
}
