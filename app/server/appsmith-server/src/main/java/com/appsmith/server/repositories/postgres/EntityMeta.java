package com.appsmith.server.repositories.postgres;

import com.appsmith.external.helpers.CustomJsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Transient;
import lombok.Getter;
import org.hibernate.annotations.Type;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fork: the table, columns and JSON columns of an entity class, as {@link SnakeCaseNamingStrategy} maps them.
 */
public final class EntityMeta {

    private static final Map<Class<?>, EntityMeta> CACHE = new ConcurrentHashMap<>();

    @Getter
    private final Class<?> type;

    @Getter
    private final String table;

    private final Map<String, Field> fields = new HashMap<>();

    private EntityMeta(Class<?> type) {
        this.type = type;
        final Entity entity = type.getAnnotation(Entity.class);
        if (entity == null) {
            throw new IllegalArgumentException(type.getName() + " is not an @Entity");
        }
        this.table = SnakeCaseNamingStrategy.quoted(
                entity.name().isEmpty() ? type.getSimpleName() : entity.name());

        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                final int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers)
                        || Modifier.isTransient(modifiers)
                        || field.isAnnotationPresent(Transient.class)) {
                    continue;
                }
                fields.putIfAbsent(field.getName(), field);
            }
        }
    }

    public static EntityMeta of(Class<?> type) {
        return CACHE.computeIfAbsent(type, EntityMeta::new);
    }

    public boolean hasField(String name) {
        return fields.containsKey(name);
    }

    public Field field(String name) {
        final Field field = fields.get(name);
        if (field == null) {
            throw new IllegalArgumentException("Unknown field '" + name + "' on " + type.getSimpleName());
        }
        return field;
    }

    public String column(String fieldName) {
        final Field field = field(fieldName);
        final Column column = field.getAnnotation(Column.class);
        if (column != null && !column.name().isEmpty()) {
            return SnakeCaseNamingStrategy.quoted(column.name());
        }
        return SnakeCaseNamingStrategy.quoted(fieldName);
    }

    public boolean isJson(String fieldName) {
        final Type typeAnnotation = field(fieldName).getAnnotation(Type.class);
        return typeAnnotation != null && CustomJsonType.class.equals(typeAnnotation.value());
    }

    public boolean isCollection(String fieldName) {
        return Collection.class.isAssignableFrom(field(fieldName).getType());
    }

    public Collection<String> fieldNames() {
        return fields.keySet();
    }
}
