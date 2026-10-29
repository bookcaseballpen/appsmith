package com.appsmith.server.repositories.postgres;

import com.appsmith.external.models.BaseDomain;
import com.appsmith.server.repositories.BaseRepositoryImpl;
import org.springframework.data.repository.core.EntityInformation;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.core.support.ReactiveRepositoryFactorySupport;
import org.springframework.data.repository.query.QueryLookupStrategy;
import org.springframework.data.repository.query.QueryMethod;
import org.springframework.data.repository.query.ValueExpressionDelegate;

import java.util.Optional;

/**
 * Fork: creates the reactive repositories on PostgreSQL: {@link BaseRepositoryImpl} for the CRUD methods, the
 * {@code Custom*Impl} fragments as before, and {@link PartTreeAppsmithQuery} for derived query methods.
 */
public class AppsmithRepositoryFactory extends ReactiveRepositoryFactorySupport {

    private final PostgresOperations postgresOperations;

    public AppsmithRepositoryFactory(PostgresOperations postgresOperations) {
        this.postgresOperations = postgresOperations;
    }

    @Override
    public <T, ID> EntityInformation<T, ID> getEntityInformation(Class<T> domainClass) {
        return new AppsmithEntityInformation<>(domainClass);
    }

    @Override
    protected Object getTargetRepository(RepositoryInformation metadata) {
        // The base class is given explicitly: the one in the repository configuration is not set for this module.
        return getTargetRepositoryViaReflection(
                BaseRepositoryImpl.class, getEntityInformation(metadata.getDomainType()), postgresOperations);
    }

    @Override
    protected Class<?> getRepositoryBaseClass(RepositoryMetadata metadata) {
        return BaseRepositoryImpl.class;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    protected Optional<QueryLookupStrategy> getQueryLookupStrategy(
            QueryLookupStrategy.Key key, ValueExpressionDelegate valueExpressionDelegate) {
        return Optional.of((method, metadata, factory, namedQueries) -> new PartTreeAppsmithQuery(
                new QueryMethod(method, metadata, factory),
                method.getReturnType(),
                (Class<? extends BaseDomain>) metadata.getDomainType(),
                postgresOperations));
    }
}
