package com.appsmith.server.repositories.ce_compatible;

import com.appsmith.server.helpers.InMemoryCacheableRepositoryHelper;
import com.appsmith.server.repositories.ce.CacheableRepositoryHelperCEImpl;
import com.appsmith.server.repositories.postgres.PostgresOperations;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CacheableRepositoryHelperCECompatibleImpl extends CacheableRepositoryHelperCEImpl
        implements CacheableRepositoryHelperCECompatible {
    public CacheableRepositoryHelperCECompatibleImpl(
            PostgresOperations postgresOperations,
            ObservationRegistry observationRegistry,
            InMemoryCacheableRepositoryHelper inMemoryCacheableRepositoryHelper) {
        super(postgresOperations, observationRegistry, inMemoryCacheableRepositoryHelper);
    }
}
