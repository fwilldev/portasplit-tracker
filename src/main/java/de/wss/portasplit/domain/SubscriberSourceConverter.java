package de.wss.portasplit.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists {@link SubscriberSource} as a plain string. See {@link SubscriberStateConverter} for why a
 * converter is preferred over {@code @Enumerated} on SQLite.
 */
@Converter(autoApply = false)
public class SubscriberSourceConverter implements AttributeConverter<SubscriberSource, String> {

    @Override
    public String convertToDatabaseColumn(SubscriberSource attribute) {
        if (attribute == null) {
            throw new IllegalStateException("TelegramSubscriber.source must not be null");
        }
        return attribute.name();
    }

    @Override
    public SubscriberSource convertToEntityAttribute(String dbData) {
        SubscriberSource source = SubscriberSource.fromString(dbData);
        if (source == null) {
            throw new IllegalStateException("Unknown subscriber source in database: " + dbData);
        }
        return source;
    }
}
