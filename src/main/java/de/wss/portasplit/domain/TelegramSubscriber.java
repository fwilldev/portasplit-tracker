package de.wss.portasplit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A Telegram recipient of availability notifications. The chat id is the natural key (one row per
 * chat). Recipients either arrive from the startup config seed ({@link SubscriberSource#ENV}) or opt
 * in through the bot ({@link SubscriberSource#BOT}); opting out is a soft delete
 * ({@link SubscriberState#UNSUBSCRIBED}) so the seed never resurrects someone who left.
 */
@Entity
@Table(name = "telegram_subscriber")
public class TelegramSubscriber {

    @Id
    @Column(name = "chat_id", length = 64)
    private String chatId;

    /** Human-friendly label (Telegram name/username), shown on the settings page. May be blank. */
    @Column(name = "display_name", length = 255)
    private String displayName;

    @Convert(converter = SubscriberStateConverter.class)
    @Column(name = "state", length = 16, nullable = false)
    private SubscriberState state = SubscriberState.PENDING;

    @Convert(converter = SubscriberSourceConverter.class)
    @Column(name = "source", length = 16, nullable = false)
    private SubscriberSource source = SubscriberSource.BOT;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected TelegramSubscriber() {
    }

    public TelegramSubscriber(String chatId, SubscriberSource source) {
        this.chatId = chatId;
        this.source = source;
    }

    public String getChatId() {
        return chatId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public SubscriberState getState() {
        return state;
    }

    public void setState(SubscriberState state) {
        this.state = state;
    }

    public SubscriberSource getSource() {
        return source;
    }

    public void setSource(SubscriberSource source) {
        this.source = source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
