package com.appsmith.server.repositories.postgres;

import com.appsmith.external.models.BaseDomain;
import com.appsmith.server.helpers.ce.bridge.Bridge;
import com.appsmith.server.helpers.ce.bridge.BridgeQuery;
import com.appsmith.server.helpers.ce.bridge.Op;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.QueryMethod;
import org.springframework.data.repository.query.RepositoryQuery;
import org.springframework.data.repository.query.parser.Part;
import org.springframework.data.repository.query.parser.PartTree;
import org.springframework.data.util.ReactiveWrappers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import static com.appsmith.server.repositories.ce.BaseAppsmithRepositoryCEImpl.notDeleted;

/**
 * Fork: executes derived query methods ({@code findByEmail}, {@code countByUserIdAndRevokedAtIsNull}, ...) of the
 * repository interfaces: the method name becomes a {@link BridgeQuery}, which runs as SQL like every other query.
 * Soft-deleted rows are excluded, as SoftDeletePartTreeMongoQuery did on MongoDB. Unsupported keywords fail when the
 * repository is created, not at the first call.
 */
public class PartTreeAppsmithQuery<T extends BaseDomain> implements RepositoryQuery {

    private final QueryMethod method;
    private final Class<?> declaredReturnType;
    private final PartTree tree;
    private final Class<T> domainClass;
    private final Class<?> returnedType;
    private final PostgresOperations postgresOperations;

    public PartTreeAppsmithQuery(
            QueryMethod method,
            Class<?> declaredReturnType,
            Class<T> domainClass,
            PostgresOperations postgresOperations) {
        this.method = method;
        this.declaredReturnType = declaredReturnType;
        this.domainClass = domainClass;
        this.postgresOperations = postgresOperations;
        this.tree = new PartTree(method.getName(), domainClass);
        this.returnedType = method.getResultProcessor().getReturnedType().getReturnedType();

        if (!ReactiveWrappers.isAvailable() || !ReactiveWrappers.supports(declaredReturnType)) {
            throw new IllegalStateException("Derived query " + method + " must return Mono or Flux (got "
                    + declaredReturnType.getName() + ")");
        }
        for (PartTree.OrPart orPart : tree) {
            for (Part part : orPart) {
                op(part); // validates
            }
        }
    }

    private static Op op(Part part) {
        return switch (part.getType()) {
            case SIMPLE_PROPERTY -> part.shouldIgnoreCase() == Part.IgnoreCaseType.NEVER ? Op.EQUAL : Op.EQ_IGNORE_CASE;
            case NEGATING_SIMPLE_PROPERTY -> Op.NOT_EQUAL;
            case IN -> Op.IN;
            case NOT_IN -> Op.NOT_IN;
            case IS_NULL -> Op.IS_NULL;
            case IS_NOT_NULL -> Op.IS_NOT_NULL;
            case TRUE -> Op.IS_TRUE;
            case FALSE -> Op.IS_FALSE;
            case EXISTS -> Op.EXISTS;
            case GREATER_THAN -> Op.GREATER_THAN;
            case GREATER_THAN_EQUAL -> Op.GREATER_THAN_OR_EQUAL;
            case LESS_THAN -> Op.LESS_THAN;
            case LESS_THAN_EQUAL -> Op.LESS_THAN_OR_EQUAL;
            default -> throw new IllegalStateException("Unsupported derived query keyword " + part.getType() + " in "
                    + part.getProperty());
        };
    }

    private static Object value(Object raw) {
        if (raw instanceof Enum<?> e) {
            return e.name();
        }
        if (raw instanceof Collection<?> c) {
            return c.stream().map(PartTreeAppsmithQuery::value).toList();
        }
        return raw;
    }

    private List<BridgeQuery<T>> criteria(Object[] parameters) {
        final Iterator<Object> args = List.of(parameters).iterator();
        final List<BridgeQuery<T>> ors = new ArrayList<>();
        for (PartTree.OrPart orPart : tree) {
            final BridgeQuery<T> and = Bridge.query();
            for (Part part : orPart) {
                final Op op = op(part);
                final String key = part.getProperty().toDotPath();
                Object value = null;
                for (int i = 0; i < part.getNumberOfArguments(); i++) {
                    value = value(args.next());
                }
                if ((op == Op.EQUAL || op == Op.NOT_EQUAL) && value == null) {
                    and.check(op == Op.EQUAL ? Op.IS_NULL : Op.IS_NOT_NULL, key, null);
                } else {
                    and.check(op, key, value);
                }
            }
            ors.add(and);
        }
        final List<BridgeQuery<T>> criteria = new ArrayList<>();
        if (ors.size() == 1) {
            if (!ors.get(0).isEmpty()) {
                criteria.add(ors.get(0));
            }
        } else if (!ors.isEmpty()) {
            criteria.add(Bridge.or(ors));
        }
        criteria.add(notDeleted());
        return criteria;
    }

    @Override
    public Object execute(Object[] parameters) {
        final List<BridgeQuery<T>> criteria = criteria(parameters);

        if (tree.isCountProjection()) {
            return postgresOperations.read(em -> PostgresOperations.count(em, domainClass, criteria));
        }
        if (tree.isExistsProjection()) {
            return postgresOperations.read(em -> PostgresOperations.count(em, domainClass, criteria) > 0);
        }
        if (tree.isDelete()) {
            final Mono<Integer> deleted =
                    postgresOperations.write(em -> PostgresOperations.delete(em, domainClass, criteria));
            return Void.class.equals(method.getReturnedObjectType()) ? deleted.then() : deleted.map(Long::valueOf);
        }

        final Sort sort = tree.getSort();
        final boolean single = !method.isCollectionQuery() && !Flux.class.isAssignableFrom(declaredReturnType);
        final int limit = tree.isLimiting() ? tree.getMaxResults() : 0;

        final Flux<?> rows = postgresOperations
                .readMany(em -> PostgresOperations.select(em, domainClass, criteria, sort, limit, 0))
                .map(row -> ProjectionMapper.map(row, returnedType));

        return single ? rows.next() : rows;
    }

    @Override
    public QueryMethod getQueryMethod() {
        return method;
    }
}
