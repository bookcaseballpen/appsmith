package com.appsmith.server.repositories.postgres;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.support.RepositoryFactoryBeanSupport;
import org.springframework.data.repository.core.support.RepositoryFactorySupport;

/**
 * Fork: the factory bean behind every repository interface, see {@link EnableAppsmithRepositories}.
 */
public class AppsmithRepositoryFactoryBean<T extends Repository<S, ID>, S, ID>
        extends RepositoryFactoryBeanSupport<T, S, ID> {

    private PostgresOperations postgresOperations;

    public AppsmithRepositoryFactoryBean(Class<? extends T> repositoryInterface) {
        super(repositoryInterface);
    }

    @Autowired
    public void setPostgresOperations(PostgresOperations postgresOperations) {
        this.postgresOperations = postgresOperations;
    }

    @Override
    protected RepositoryFactorySupport createRepositoryFactory() {
        return new AppsmithRepositoryFactory(postgresOperations);
    }
}
