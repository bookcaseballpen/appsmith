package com.appsmith.server.repositories;

import com.appsmith.external.models.BaseDomain;
import com.appsmith.server.constants.FieldName;
import com.appsmith.server.helpers.ce.bridge.Bridge;
import com.appsmith.server.helpers.ce.bridge.BridgeQuery;
import com.appsmith.server.helpers.ce.bridge.BridgeUpdate;
import com.appsmith.server.repositories.postgres.PostgresOperations;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.core.EntityInformation;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.appsmith.server.repositories.ce.BaseAppsmithRepositoryCEImpl.notDeleted;

/**
 * This repository implementation is the base class that will be used by Spring Data running all the default queries.
 * It filters out records marked as deleted.
 * To enable this base implementation, it is the base class of the repository factory
 * ({@link com.appsmith.server.repositories.postgres.AppsmithRepositoryFactory}).
 * <p>
 * Fork: implemented on PostgreSQL with {@link PostgresOperations}; it used to extend Spring Data MongoDB's
 * {@code SimpleReactiveMongoRepository}.
 *
 * @param <T>  The domain class that extends {@link BaseDomain}. This is required because we use default fields in
 *             {@link BaseDomain} such as `deletedAt`
 * @param <ID> The ID field that extends Serializable interface
 *             <p>
 *             In case you are wondering why we have two different repository implementation classes i.e.
 *             BaseRepositoryImpl.java and BaseAppsmithRepositoryCEImpl.java, Arpit's comments on this might be helpful:
 *             ```
 *             BaseRepository is required for running any JPA queries. This doesn’t invoke any ACL permissions. This is used when
 *             we wish to fetch data from the DB without ACL. For eg, Fetching a user by username during login
 *             AppsmithRepository is the one that we should use by default (unless the use case demands that we don’t need ACL).
 *             It is implemented by BaseAppsmithRepositoryCEImpl and BaseAppsmithRepositoryImpl.
 *             ```
 */
@Slf4j
public class BaseRepositoryImpl<T extends BaseDomain, ID extends Serializable> implements BaseRepository<T, ID> {

    protected final EntityInformation<T, ID> entityInformation;
    protected final PostgresOperations postgresOperations;

    public BaseRepositoryImpl(
            @NonNull EntityInformation<T, ID> entityInformation, @NonNull PostgresOperations postgresOperations) {
        this.entityInformation = entityInformation;
        this.postgresOperations = postgresOperations;
    }

    private Class<T> type() {
        return entityInformation.getJavaType();
    }

    private static <T extends BaseDomain> BridgeQuery<T> idIs(Object id) {
        return Bridge.equal(FieldName.ID, String.valueOf(id));
    }

    private Mono<List<T>> select(List<BridgeQuery<T>> criteria, Sort sort, int limit) {
        return postgresOperations.read(em -> PostgresOperations.select(em, type(), criteria, sort, limit, 0));
    }

    @Override
    public Mono<T> findById(ID id) {
        Assert.notNull(id, "The given id must not be null!");
        return select(List.of(idIs(id), notDeleted()), null, 1).flatMap(rows -> Mono.justOrEmpty(first(rows)));
    }

    @Override
    public Mono<T> findById(Publisher<ID> id) {
        return Mono.from(id).flatMap(this::findById);
    }

    private static <T> T first(List<T> rows) {
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public Mono<Boolean> existsById(ID id) {
        return findById(id).hasElement();
    }

    @Override
    public Mono<Boolean> existsById(Publisher<ID> id) {
        return Mono.from(id).flatMap(this::existsById);
    }

    @Override
    public Flux<T> findAll() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .map(auth -> auth.getPrincipal())
                .flatMapMany(principal -> retrieveAll());
    }

    @Override
    public Flux<T> findAll(Sort sort) {
        return select(List.of(notDeleted()), sort, 0).flatMapIterable(rows -> rows);
    }

    @Override
    public Flux<T> retrieveAll() {
        return select(List.of(notDeleted()), null, 0).flatMapIterable(rows -> rows);
    }

    @Override
    public Flux<T> findAllById(Iterable<ID> ids) {
        final List<String> idList = new ArrayList<>();
        ids.forEach(id -> idList.add(String.valueOf(id)));
        if (idList.isEmpty()) {
            return Flux.empty();
        }
        return select(List.of(Bridge.in(FieldName.ID, idList), notDeleted()), null, 0)
                .flatMapIterable(rows -> rows);
    }

    @Override
    public Flux<T> findAllById(Publisher<ID> idStream) {
        return Flux.from(idStream).collectList().flatMapMany(this::findAllById);
    }

    @Override
    public Mono<Long> count() {
        return postgresOperations.read(em -> PostgresOperations.count(em, type(), List.of(notDeleted())));
    }

    @Override
    public <S extends T> Mono<S> save(S entity) {
        Assert.notNull(entity, "Entity must not be null!");
        return postgresOperations.write(em -> PostgresOperations.save(em, entity));
    }

    @Override
    public <S extends T> Flux<S> saveAll(Iterable<S> entities) {
        final List<S> list = new ArrayList<>();
        entities.forEach(list::add);
        if (list.isEmpty()) {
            return Flux.empty();
        }
        return postgresOperations
                .write(em -> {
                    final List<S> saved = new ArrayList<>(list.size());
                    for (S entity : list) {
                        saved.add(PostgresOperations.save(em, entity));
                    }
                    return saved;
                })
                .flatMapIterable(saved -> saved);
    }

    @Override
    public <S extends T> Flux<S> saveAll(Publisher<S> entityStream) {
        return Flux.from(entityStream).concatMap(this::save);
    }

    @Override
    public <S extends T> Mono<S> insert(S entity) {
        return save(entity);
    }

    @Override
    public <S extends T> Flux<S> insert(Iterable<S> entities) {
        return saveAll(entities);
    }

    @Override
    public Mono<Void> deleteById(ID id) {
        return postgresOperations
                .write(em -> PostgresOperations.delete(em, type(), List.of(idIs(id))))
                .then();
    }

    @Override
    public Mono<Void> deleteById(Publisher<ID> id) {
        return Mono.from(id).flatMap(this::deleteById);
    }

    @Override
    public Mono<Void> delete(T entity) {
        return deleteById(entityInformation.getRequiredId(entity));
    }

    @Override
    public Mono<Void> deleteAllById(Iterable<? extends ID> ids) {
        final List<String> idList = new ArrayList<>();
        ids.forEach(id -> idList.add(String.valueOf(id)));
        if (idList.isEmpty()) {
            return Mono.empty();
        }
        return postgresOperations
                .write(em -> PostgresOperations.delete(em, type(), List.of(Bridge.in(FieldName.ID, idList))))
                .then();
    }

    @Override
    public Mono<Void> deleteAll(Iterable<? extends T> entities) {
        final List<ID> ids = new ArrayList<>();
        entities.forEach(e -> ids.add(entityInformation.getRequiredId(e)));
        return deleteAllById(ids);
    }

    @Override
    public Mono<Void> deleteAll(Publisher<? extends T> entityStream) {
        return Flux.from(entityStream).concatMap(this::delete).then();
    }

    @Override
    public Mono<Void> deleteAll() {
        return postgresOperations
                .write(em -> PostgresOperations.delete(em, type(), List.of()))
                .then();
    }

    @Override
    public Mono<T> archive(T entity) {
        Assert.notNull(entity, "The given entity must not be null!");
        Assert.notNull(entity.getId(), "The given entity's id must not be null!");
        // Entity is already deleted
        if (entity.isDeleted()) {
            return Mono.just(entity);
        }

        entity.setDeletedAt(Instant.now());
        return save(entity);
    }

    @Override
    public Mono<Boolean> archiveById(ID id) {
        Assert.notNull(id, "The given id must not be null!");

        return postgresOperations
                .write(em ->
                        PostgresOperations.update(em, type(), List.of(idIs(id), notDeleted()), getForArchive(), true))
                .map(count -> count > 0);
    }

    public BridgeUpdate getForArchive() {
        return Bridge.update().set(FieldName.DELETED_AT, Instant.now());
    }

    @Override
    public Mono<Boolean> archiveAllById(Collection<ID> ids) {
        Assert.notNull(ids, "The given ids must not be null!");
        Assert.notEmpty(ids, "The given list of ids must not be empty!");

        final List<String> idList = ids.stream().map(String::valueOf).toList();
        return postgresOperations
                .write(em -> PostgresOperations.update(
                        em, type(), List.of(Bridge.in(FieldName.ID, idList), notDeleted()), getForArchive(), false))
                .map(count -> count > 0);
    }
}
