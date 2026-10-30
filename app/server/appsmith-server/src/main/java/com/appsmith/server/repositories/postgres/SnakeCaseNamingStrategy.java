package com.appsmith.server.repositories.postgres;

import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.naming.PhysicalNamingStrategy;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;

/**
 * Fork: table and column names are the snake_case of the class and field names, always quoted ({@code "user"} is a
 * reserved word). {@link EntityMeta} uses the same function for the SQL the repositories write, so Hibernate's
 * mapping and the hand-written SQL can never disagree.
 */
public class SnakeCaseNamingStrategy implements PhysicalNamingStrategy {

    public static String snake(String name) {
        final StringBuilder sb = new StringBuilder(name.length() + 8);
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                final char before = name.charAt(i - 1);
                final boolean nextIsLower = i + 1 < name.length() && Character.isLowerCase(name.charAt(i + 1));
                if (Character.isLowerCase(before)
                        || Character.isDigit(before)
                        || (Character.isUpperCase(before) && nextIsLower)) {
                    sb.append('_');
                }
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    public static String quoted(String name) {
        return "\"" + snake(name) + "\"";
    }

    private static Identifier apply(Identifier name) {
        return name == null ? null : Identifier.toIdentifier(snake(name.getText()), true);
    }

    @Override
    public Identifier toPhysicalCatalogName(Identifier name, JdbcEnvironment jdbcEnvironment) {
        return name;
    }

    @Override
    public Identifier toPhysicalSchemaName(Identifier name, JdbcEnvironment jdbcEnvironment) {
        return name;
    }

    @Override
    public Identifier toPhysicalTableName(Identifier name, JdbcEnvironment jdbcEnvironment) {
        return apply(name);
    }

    @Override
    public Identifier toPhysicalSequenceName(Identifier name, JdbcEnvironment jdbcEnvironment) {
        return apply(name);
    }

    @Override
    public Identifier toPhysicalColumnName(Identifier name, JdbcEnvironment jdbcEnvironment) {
        return apply(name);
    }
}
