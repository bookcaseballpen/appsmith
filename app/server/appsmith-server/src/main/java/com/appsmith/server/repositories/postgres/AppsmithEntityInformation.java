package com.appsmith.server.repositories.postgres;

import com.appsmith.external.models.BaseDomain;
import org.springframework.data.repository.core.support.AbstractEntityInformation;

/**
 * Fork: entity information for the repositories: every domain is a {@link BaseDomain} with a String id.
 */
public class AppsmithEntityInformation<T, ID> extends AbstractEntityInformation<T, ID> {

    public AppsmithEntityInformation(Class<T> domainClass) {
        super(domainClass);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ID getId(T entity) {
        return entity instanceof BaseDomain domain ? (ID) domain.getId() : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<ID> getIdType() {
        return (Class<ID>) String.class;
    }
}
