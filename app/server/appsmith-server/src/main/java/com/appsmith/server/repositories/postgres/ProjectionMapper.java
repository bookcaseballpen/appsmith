package com.appsmith.server.repositories.postgres;

import org.springframework.beans.BeanUtils;
import org.springframework.data.projection.SpelAwareProxyProjectionFactory;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;

/**
 * Fork: turns a loaded entity into a projection class. Mongo loaded only the projected fields; here the whole row is
 * loaded and copied, by name, into the projection: an interface (a proxy over the entity), a record (canonical
 * constructor) or a class (no-arg constructor, then fields), nested projections included.
 */
public final class ProjectionMapper {

    private static final SpelAwareProxyProjectionFactory PROXY_FACTORY = new SpelAwareProxyProjectionFactory();

    private ProjectionMapper() {}

    @SuppressWarnings("unchecked")
    public static <P> P map(Object source, Class<P> projectionClass) {
        if (source == null) {
            return null;
        }
        if (projectionClass.isInstance(source)) {
            return (P) source;
        }
        if (projectionClass.isInterface()) {
            return PROXY_FACTORY.createProjection(projectionClass, source);
        }
        try {
            if (projectionClass.isRecord()) {
                final RecordComponent[] components = projectionClass.getRecordComponents();
                final Class<?>[] types = new Class<?>[components.length];
                final Object[] args = new Object[components.length];
                for (int i = 0; i < components.length; i++) {
                    types[i] = components[i].getType();
                    args[i] = convert(read(source, components[i].getName()), types[i]);
                }
                final Constructor<P> constructor = projectionClass.getDeclaredConstructor(types);
                constructor.setAccessible(true);
                return constructor.newInstance(args);
            }
            final P target = BeanUtils.instantiateClass(projectionClass);
            for (Class<?> c = projectionClass; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                        continue;
                    }
                    field.setAccessible(true);
                    field.set(target, convert(read(source, field.getName()), field.getType()));
                }
            }
            return target;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot map " + source.getClass() + " to " + projectionClass, e);
        }
    }

    private static Object convert(Object value, Class<?> type) {
        if (value == null || type.isInstance(value) || type.isPrimitive()) {
            return value;
        }
        if (type.getName().startsWith("java.")) {
            return value;
        }
        return map(value, type);
    }

    private static Object read(Object source, String name) {
        for (Class<?> c = source.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            final Field field = ReflectionUtils.findField(c, name);
            if (field != null) {
                ReflectionUtils.makeAccessible(field);
                return ReflectionUtils.getField(field, source);
            }
        }
        return null;
    }
}
