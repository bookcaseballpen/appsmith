package com.appsmith.server.repositories.postgres;

import com.appsmith.external.models.BaseDomain;
import com.appsmith.server.helpers.ce.bridge.BridgeQuery;
import com.appsmith.server.helpers.ce.bridge.BridgeUpdate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * Fork: runs the blocking JPA/JDBC work of the repositories on {@code boundedElastic}, each call in its own
 * transaction, and offers the SQL statements the reactive repositories are built from. Callers keep the reactive
 * API they had on MongoDB.
 */
@Slf4j
@Component
public class PostgresOperations {

    @PersistenceContext
    private EntityManager entityManager;

    private final TransactionTemplate writeTemplate;
    private final TransactionTemplate readTemplate;

    public PostgresOperations(PlatformTransactionManager transactionManager) {
        this.writeTemplate = new TransactionTemplate(transactionManager);
        this.readTemplate = new TransactionTemplate(transactionManager);
        this.readTemplate.setReadOnly(true);
        this.readTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    public <R> Mono<R> read(Function<EntityManager, R> work) {
        return Mono.fromCallable(() -> readTemplate.execute(status -> work.apply(entityManager)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public <R> Flux<R> readMany(Function<EntityManager, List<R>> work) {
        return read(work).flatMapIterable(list -> list);
    }

    public <R> Mono<R> write(Function<EntityManager, R> work) {
        return Mono.fromCallable(() -> writeTemplate.execute(status -> work.apply(entityManager)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Runs {@code work} in a transaction on the calling thread; for code that is already blocking. */
    public <R> R writeBlocking(Function<EntityManager, R> work) {
        return writeTemplate.execute(status -> work.apply(entityManager));
    }

    // ---- Statements (call inside read/write) -------------------------------------------------------------------

    private static void bind(Query query, List<Object> params) {
        for (int i = 0; i < params.size(); i++) {
            query.setParameter(i + 1, params.get(i));
        }
    }

    @SuppressWarnings("unchecked")
    public static <T extends BaseDomain> List<T> select(
            EntityManager em,
            Class<T> type,
            Collection<BridgeQuery<T>> criteria,
            Sort sort,
            int limit,
            int skip) {
        final SqlRenderer sql = new SqlRenderer(EntityMeta.of(type));
        final StringBuilder statement = new StringBuilder("SELECT * FROM ")
                .append(sql.meta().getTable())
                .append(" WHERE ")
                .append(sql.where(List.copyOf(criteria)))
                .append(sql.orderBy(sort));
        if (limit > 0) {
            statement.append(" LIMIT ").append(limit);
        }
        if (skip > 0) {
            statement.append(" OFFSET ").append(skip);
        }
        final Query query = em.createNativeQuery(statement.toString(), type);
        bind(query, sql.params());
        final List<T> rows = query.getResultList();
        // Detach, so that changes callers make to the objects are never flushed by accident.
        rows.forEach(em::detach);
        return rows;
    }

    public static <T extends BaseDomain> long count(EntityManager em, Class<T> type, Collection<BridgeQuery<T>> criteria) {
        final SqlRenderer sql = new SqlRenderer(EntityMeta.of(type));
        final String statement = "SELECT count(*) FROM " + sql.meta().getTable() + " WHERE "
                + sql.where(List.copyOf(criteria));
        final Query query = em.createNativeQuery(statement);
        bind(query, sql.params());
        return ((Number) query.getSingleResult()).longValue();
    }

    /**
     * Applies {@code update} to the matching rows, or only to the first one when {@code firstOnly}. Returns the number
     * of matched rows, like Mongo's matched count.
     */
    public static <T extends BaseDomain> int update(
            EntityManager em,
            Class<T> type,
            Collection<BridgeQuery<T>> criteria,
            BridgeUpdate update,
            boolean firstOnly) {
        if (update.isEmpty()) {
            return 0;
        }
        final SqlRenderer sql = new SqlRenderer(EntityMeta.of(type));
        final String table = sql.meta().getTable();
        final String id = sql.column(BaseDomain.Fields.id);
        // The SET part is rendered first, so that its parameters come first.
        final String set = sql.set(update);
        final String where = sql.where(List.copyOf(criteria));
        final String statement = firstOnly
                ? "UPDATE " + table + " SET " + set + " WHERE " + id + " = (SELECT " + id + " FROM " + table
                        + " WHERE " + where + " LIMIT 1)"
                : "UPDATE " + table + " SET " + set + " WHERE " + where;
        final Query query = em.createNativeQuery(statement);
        bind(query, sql.params());
        return query.executeUpdate();
    }

    public static <T extends BaseDomain> int delete(EntityManager em, Class<T> type, Collection<BridgeQuery<T>> criteria) {
        final SqlRenderer sql = new SqlRenderer(EntityMeta.of(type));
        final String statement = "DELETE FROM " + sql.meta().getTable() + " WHERE " + sql.where(List.copyOf(criteria));
        final Query query = em.createNativeQuery(statement);
        bind(query, sql.params());
        return query.executeUpdate();
    }

    /**
     * Inserts or replaces {@code entity} (Mongo's save). Returns the same object, with its id and timestamps set, so
     * that fields which are not stored (user permissions and the like) stay as the caller set them.
     */
    public static <T extends BaseDomain> T save(EntityManager em, T entity) {
        final BaseDomain existing = entity.getId() == null ? null : em.find(entity.getClass(), entity.getId());
        if (existing == null) {
            // Assigns the id before Hibernate looks at it.
            entity.prePersist();
            em.persist(entity);
            em.flush();
            em.detach(entity);
            return entity;
        }
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(existing.getCreatedAt());
        }
        entity.preUpdate();
        em.merge(entity);
        em.flush();
        em.clear();
        return entity;
    }
}
