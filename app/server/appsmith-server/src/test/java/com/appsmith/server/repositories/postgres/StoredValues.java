package com.appsmith.server.repositories.postgres;

/**
 * Fork: values as they are stored in PostgreSQL, for tests that check the stored form. Mongo's save encrypted the
 * entity in place, so upstream tests read the encrypted value from the saved object; here the entity keeps the plain
 * value and only the stored JSON is encrypted.
 */
public final class StoredValues {

    private StoredValues() {}

    /** The stored (encrypted) password of a datasource's storage in one environment. */
    public static String datasourcePassword(
            PostgresOperations postgresOperations, String datasourceId, String environmentId) {
        return postgresOperations
                .read(em -> (String)
                        em.createNativeQuery("SELECT datasource_configuration -> 'authentication' ->> 'password'"
                                        + " FROM datasource_storage WHERE datasource_id = ?1 AND environment_id = ?2")
                                .setParameter(1, datasourceId)
                                .setParameter(2, environmentId)
                                .getSingleResult())
                .block();
    }
}
