package com.appsmith.server.repositories.postgres;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Fork: PostgreSQL {@code text} cannot hold the NUL character. For JSON values stored as text (such as the Tern
 * definitions of a custom JS library), a raw NUL is written as the JSON escape {@code \u0000}, which reads back as the
 * same JSON value.
 */
@Converter
public class NulSafeTextConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String value) {
        return value == null ? null : value.replace("\u0000", "\\u0000");
    }

    @Override
    public String convertToEntityAttribute(String value) {
        return value;
    }
}
