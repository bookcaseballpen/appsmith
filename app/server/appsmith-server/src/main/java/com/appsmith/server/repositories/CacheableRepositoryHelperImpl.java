package com.appsmith.server.repositories;

import com.appsmith.server.helpers.InMemoryCacheableRepositoryHelper;
import com.appsmith.server.repositories.ce_compatible.CacheableRepositoryHelperCECompatibleImpl;
import com.appsmith.server.repositories.postgres.PostgresOperations;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

@Component
public class CacheableRepositoryHelperImpl extends CacheableRepositoryHelperCECompatibleImpl
        implements CacheableRepositoryHelper {
    public CacheableRepositoryHelperImpl(
            PostgresOperations postgresOperations,
            ObservationRegistry observationRegistry,
            InMemoryCacheableRepositoryHelper inMemoryCacheableRepositoryHelper) {
        super(postgresOperations, observationRegistry, inMemoryCacheableRepositoryHelper);
    }
}
