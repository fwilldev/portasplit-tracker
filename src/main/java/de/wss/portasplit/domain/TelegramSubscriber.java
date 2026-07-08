package de.wss.portasplit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A Telegram chat that may receive notifications. Recipients are managed at runtime: a person opts in
 * by sending {@code /start} to the bot and confirming (→ {@link State#CONFIRMED}); the chat ids from
 * {@code TELEGRAM_CHAT_ID} are seeded into this table on startup (source {@link Source#ENV}). Only
 * {@link State#CONFIRMED} subscribers are messaged.
 */
@Entity
@Table(name = "telegram_subscriber")
public class TelegramSubscriber {

    /** Lifecycle of an opt-in. Messages go only to {@link #CONFIRMED}. */
    public enum State {
        /** Sent {@code /start} but has not pressed the confirm button yet. */
        PENDING,
        /** Confirmed — receives notifications. */
        CONFIRMED,
        /** Opted out via {@code /stop} or removed in the UI. Kept as a tombstone so the env seed
         *  merge does not silently re-subscribe them on the next restart. */
        UNSUBSCRIBED
    }

    /** Where the subscriber came from. */
    public enum Source {
        /** Seeded from the {@code TELEGRAM_CHAT_ID} env config. */
        ENV,
        /** Self-service opt-in through the bot. */
        BOT
    }

    /** The Telegram chat id (== user id for a private chat); the value we send messages to. */
    @Id
    @Column(name = "chat_id", length = 64)
    private String chatId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 16, nullable = false)
    private State state = State.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 16, nullable = false)
    private Source source = Source.BOT;

    @Column(name = "first_name", length = 256)
    private String firstName;

    @Column(name = "username", length = 256)
    private String username;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    public TelegramSubscriber() {
    }

    public TelegramSubscriber(String chatId) {
        this.chatId = chatId;
    }

    /** Best-effort display label: first name, else @username, else the raw chat id. */
    public String displayName() {
        if (firstName != null && !firstName.isBlank()) {
            return firstName;
        }
        if (username != null && !username.isBlank()) {
            return "@" + username;
        }
        return chatId;
    }

    public String getChatId() {
        return chatId;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public Source getSource() {
        return source;
    }

    public void setSource(Source source) {
        this.source = source;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
