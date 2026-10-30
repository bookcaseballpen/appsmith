package com.appsmith.server.repositories;

import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.repository.reactive.ReactiveSortingRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.Serializable;
import java.util.Collection;

/**
 * Fork: extends Spring Data's store-neutral reactive interfaces instead of {@code ReactiveMongoRepository}. The
 * implementation is {@link BaseRepositoryImpl}, on PostgreSQL.
 */
@NoRepositoryBean
public interface BaseRepository<T, ID extends Serializable>
        extends ReactiveCrudRepository<T, ID>, ReactiveSortingRepository<T, ID> {

    /**
     * This function sets the deleted flag to true and then saves the modified document.
     *
     * @param entity The entity which needs to be archived
     * @return Mono<T>
     */
    Mono<T> archive(T entity);

    /**
     * This function directly updates the document by setting the deleted flag to true for the entity with the given id
     *
     * @param id The id of the document that needs to be archived
     * @return
     */
    Mono<Boolean> archiveById(ID id);

    /**
     * This function directly updates the DB by setting the deleted flag to true for all the documents in the collection
     * with the given list of ids.
     *
     * @param ids The list of ids of the document that needs to be archived.
     * @return
     */
    Mono<Boolean> archiveAllById(Collection<ID> ids);

    /**
     * This function retrieves all the documents in the collection without the user context. This method will be used
     * by internal server workflows like crons etc. to fetch all the documents from the collection.
     * @return
     */
    Flux<T> retrieveAll();

    /**
     * Inserts a new document (Mongo's insert). Fork: the same as save for a new entity.
     */
    <S extends T> Mono<S> insert(S entity);

    <S extends T> Flux<S> insert(Iterable<S> entities);
}
