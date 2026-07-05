package de.wss.portasplit.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists {@link SubscriberState} as a plain string. Using a converter (instead of
 * {@code @Enumerated}) stops Hibernate from generating a {@code CHECK (state in (...))} constraint,
 * which SQLite cannot drop in place and would reject a future state value on an existing database.
 */
@Converter(autoApply = false)
public class SubscriberStateConverter implements AttributeConverter<SubscriberState, String> {

    @Override
    public String convertToDatabaseColumn(SubscriberState attribute) {
        if (attribute == null) {
            throw new IllegalStateException("TelegramSubscriber.state must not be null");
        }
        return attribute.name();
    }

    @Override
    public SubscriberState convertToEntityAttribute(String dbData) {
        SubscriberState state = SubscriberState.fromString(dbData);
        if (state == null) {
            throw new IllegalStateException("Unknown subscriber state in database: " + dbData);
        }
        return state;
    }
}
