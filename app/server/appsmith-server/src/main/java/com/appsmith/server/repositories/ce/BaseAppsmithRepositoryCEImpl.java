package com.appsmith.server.repositories.ce;

import com.appsmith.external.helpers.AppsmithBeanUtils;
import com.appsmith.external.models.BaseDomain;
import com.appsmith.external.models.Policy;
import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.constants.FieldName;
import com.appsmith.server.domains.User;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.helpers.ce.bridge.Bridge;
import com.appsmith.server.helpers.ce.bridge.BridgeQuery;
import com.appsmith.server.helpers.ce.bridge.BridgeUpdate;
import com.appsmith.server.repositories.CacheableRepositoryHelper;
import com.appsmith.server.repositories.ce.params.QueryAllParams;
import com.appsmith.server.repositories.postgres.PostgresOperations;
import com.appsmith.server.repositories.postgres.ProjectionMapper;
import jakarta.persistence.Transient;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.GenericTypeResolver;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ReflectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * In case you are wondering why we have two different repository implementation classes i.e.
 * BaseRepositoryImpl.java and BaseAppsmithRepositoryCEImpl.java, Arpit's comments on this might be helpful:
 * ```
 * BaseRepository is required for running any JPA queries. This doesn’t invoke any ACL permissions. This is used when
 * we wish to fetch data from the DB without ACL. For eg, Fetching a user by username during login
 * Usage example:
 * ActionCollectionRepositoryCE extends BaseRepository to power JPA queries using the ReactiveMongoRepository.
 * AppsmithRepository is the one that we should use by default (unless the use case demands that we don’t need ACL).
 * It is implemented by BaseAppsmithRepositoryCEImpl and BaseAppsmithRepositoryImpl. This interface allows us to
 * define custom Mongo queries by including the delete functionality & ACL permissions.
 * Usage example:
 * CustomActionCollectionRepositoryCE extends AppsmithRepository and then implements the functions defined there.
 * I agree that the naming is a little confusing. Open to hearing better naming suggestions so that we can improve
 * the understanding of these interfaces.
 * ```
 * Ref: https://theappsmith.slack.com/archives/CPQNLFHTN/p1669100205502599?thread_ts=1668753437.497369&cid=CPQNLFHTN
 * <p>
 * Note, we use the {@code @Autowired} annotation for bean injection here, instead of using constructor injection. This
 * is an intentional exception to the usual recommendation. The reason is that this class is a base class for all other
 * repository classes, and using constructor params would require all repository classes to have the same constructor
 * params, and corresponding {@code super} calls. This was causing a lot of conflicts between CE and EE, and other
 * additional overhead, with very little value to speak for. Hence, we are using {@code @Autowired} here.
 * <p>
 * <a href="https://theappsmith.slack.com/archives/CPQNLFHTN/p1711966160274399">Ref Slack thread</a>.
 * <p>
 * Fork: runs on PostgreSQL. The API is unchanged (reactive, database-neutral {@link BridgeQuery} criteria); the
 * queries run as SQL through {@link PostgresOperations}, and the ACL check is a jsonb test on {@code policy_map}.
 */
public abstract class BaseAppsmithRepositoryCEImpl<T extends BaseDomain> {

    @Autowired
    private PostgresOperations postgresOperations;

    protected final Class<T> genericDomain;

    @Autowired
    private CacheableRepositoryHelper cacheableRepositoryHelper;

    public static final int NO_RECORD_LIMIT = -1;

    public static final int NO_SKIP = 0;

    @SuppressWarnings("unchecked")
    public BaseAppsmithRepositoryCEImpl() {
        this.genericDomain =
                (Class<T>) GenericTypeResolver.resolveTypeArgument(getClass(), BaseAppsmithRepositoryCEImpl.class);
    }

    protected PostgresOperations getPostgresOperations() {
        return postgresOperations;
    }

    public static <T extends BaseDomain> BridgeQuery<T> notDeleted() {
        return Bridge.isNull(FieldName.DELETED_AT);
    }

    /**
     * The ACL criteria: one of {@code permissionGroups} is granted {@code permission}. Returns null when there is no
     * permission to check. Fork: a hook (it replaces Mongo's {@code userAcl}) so that subclasses can narrow what a
     * group may see, see {@link com.appsmith.server.repositories.CustomApplicationRepositoryImpl}.
     */
    protected BridgeQuery<T> permissionCriteria(Set<String> permissionGroups, AclPermission permission) {
        if (permission == null) {
            return null;
        }
        return Bridge.<T>query().hasPermission(permission.getValue(), permissionGroups);
    }

    public Mono<T> findById(String id, AclPermission permission) {
        return queryBuilder().byId(id).permission(permission).one();
    }

    public Mono<T> updateById(@NonNull String id, @NonNull T resource, AclPermission permission) {
        // Set policies to null in the update object
        resource.setPolicies(null);

        final QueryAllParams<T> q = queryBuilder().byId(id).permission(permission);

        return q.updateFirst(buildUpdateFromSparseResource(resource)).then(Mono.defer(q::one));
    }

    public Mono<Integer> updateByIdWithoutPermissionCheck(@NonNull String id, BridgeUpdate update) {
        return queryBuilder().byId(id).updateFirst(update);
    }

    public Mono<Integer> updateFieldByBaseIdAndBranchName(
            String baseId,
            String baseIdPath,
            Map<String, Object> fieldNameValueMap,
            String branchName,
            String branchNamePath,
            AclPermission permission) {
        final QueryAllParams<T> builder = queryBuilder();

        builder.criteria(Bridge.equal(baseIdPath, baseId));

        if (!isBlank(branchName)) {
            builder.criteria(Bridge.equal(branchNamePath, branchName));
        }

        final BridgeUpdate update = Bridge.update();
        fieldNameValueMap.forEach(update::set);

        return builder.permission(permission).updateFirst(update);
    }

    public Mono<Integer> updateFieldById(
            String id, String idPath, Map<String, Object> fieldNameValueMap, AclPermission permission) {
        final QueryAllParams<T> builder = queryBuilder();

        builder.criteria(Bridge.equal(idPath, id));

        final BridgeUpdate update = Bridge.update();
        fieldNameValueMap.forEach(update::set);

        return builder.permission(permission).updateFirst(update);
    }

    protected Mono<Set<String>> getCurrentUserPermissionGroupsIfRequired(AclPermission permission) {
        return getCurrentUserPermissionGroupsIfRequired(permission, true);
    }

    protected Mono<Set<String>> getCurrentUserPermissionGroupsIfRequired(
            AclPermission permission, boolean includeAnonymousUserPermissions) {
        return permission == null
                ? Mono.just(Set.of())
                : getCurrentUserPermissionGroups(includeAnonymousUserPermissions);
    }

    public Mono<Set<String>> getCurrentUserPermissionGroups() {
        return getCurrentUserPermissionGroups(true);
    }

    protected Mono<Set<String>> getCurrentUserPermissionGroups(boolean includeAnonymousUserPermissions) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (User) ctx.getAuthentication().getPrincipal())
                .flatMap(user -> includeAnonymousUserPermissions
                        ? getAllPermissionGroupsForUser(user)
                        : getStrictPermissionGroupsForUser(user));
    }

    /**
     * All the criteria of a query: the caller's, not-deleted, and the ACL check.
     */
    protected List<BridgeQuery<T>> criteriaWithPermission(
            List<BridgeQuery<T>> criterias, Set<String> permissionGroups, AclPermission aclPermission) {
        final List<BridgeQuery<T>> criteriaList = new ArrayList<>(criterias);
        criteriaList.add(notDeleted());

        final BridgeQuery<T> permissionCriteria = permissionCriteria(permissionGroups, aclPermission);
        if (permissionCriteria != null) {
            criteriaList.add(permissionCriteria);
        }

        return criteriaList;
    }

    public QueryAllParams<T> queryBuilder() {
        return new QueryAllParams<>(this);
    }

    public Flux<T> queryAllExecute(QueryAllParams<T> params) {
        return queryAllExecute(params, this.genericDomain)
                .flatMap(obj -> setUserPermissionsInObject(obj, params.getPermissionGroups()));
    }

    public <P> Flux<P> queryAllExecute(QueryAllParams<T> params, Class<P> projectionClass) {
        return ensurePermissionGroupsInParams(params).thenMany(Flux.defer(() -> {
            final AclPermission permission = params.getPermission();
            final Set<String> permissionGroups = params.getPermissionGroups();

            if (permission != null && CollectionUtils.isEmpty(permissionGroups)) {
                // There's a permission we want to check, but there's zero permission groups to check against. The
                // resulting query will _always_ be `false`. So returning an early response, without hitting the DB.
                return Flux.empty();
            }

            final List<BridgeQuery<T>> criteria =
                    criteriaWithPermission(params.getCriteria(), permissionGroups, permission);

            return postgresOperations
                    .readMany(em -> PostgresOperations.select(
                            em,
                            genericDomain,
                            criteria,
                            params.getSort(),
                            params.getLimit() == NO_RECORD_LIMIT ? 0 : params.getLimit(),
                            params.getSkip()))
                    .map(row -> ProjectionMapper.map(row, projectionClass));
        }));
    }

    public Mono<T> queryOneExecute(QueryAllParams<T> params) {
        return queryOneExecute(params, this.genericDomain)
                .flatMap(obj -> setUserPermissionsInObject(obj, params.getPermissionGroups()));
    }

    public <P> Mono<P> queryOneExecute(QueryAllParams<T> params, Class<P> projectionClass) {
        return ensurePermissionGroupsInParams(params).then(Mono.defer(() -> {
            final AclPermission permission = params.getPermission();
            final Set<String> permissionGroups = params.getPermissionGroups();

            if (permission != null && CollectionUtils.isEmpty(permissionGroups)) {
                // There's a permission we want to check, but there's zero permission groups to check against. The
                // resulting query will _always_ be `false`. So returning an early response, without hitting the DB.
                return Mono.empty();
            }

            final List<BridgeQuery<T>> criteria =
                    criteriaWithPermission(params.getCriteria(), permissionGroups, permission);

            // Like Mongo's `one()`, more than one match is an error.
            return postgresOperations
                    .read(em -> {
                        final List<T> rows = PostgresOperations.select(em, genericDomain, criteria, null, 2, 0);
                        if (rows.size() > 1) {
                            throw new IllegalStateException("Query for one " + genericDomain.getSimpleName()
                                    + " returned more than one result");
                        }
                        return rows.isEmpty() ? null : rows.get(0);
                    })
                    .map(row -> ProjectionMapper.map(row, projectionClass));
        }));
    }

    public Mono<T> queryFirstExecute(QueryAllParams<T> params) {
        return ensurePermissionGroupsInParams(params).then(Mono.defer(() -> {
            final AclPermission permission = params.getPermission();
            final Set<String> permissionGroups = params.getPermissionGroups();

            if (permission != null && CollectionUtils.isEmpty(permissionGroups)) {
                // There's a permission we want to check, but there's zero permission groups to check against. The
                // resulting query will _always_ be `false`. So returning an early response, without hitting the DB.
                return Mono.empty();
            }

            final List<BridgeQuery<T>> criteria =
                    criteriaWithPermission(params.getCriteria(), permissionGroups, permission);

            return postgresOperations
                    .read(em -> {
                        final List<T> rows =
                                PostgresOperations.select(em, genericDomain, criteria, params.getSort(), 1, 0);
                        return rows.isEmpty() ? null : rows.get(0);
                    })
                    .flatMap(obj -> setUserPermissionsInObject(obj, permissionGroups));
        }));
    }

    public Mono<Long> countExecute(QueryAllParams<T> params) {
        return ensurePermissionGroupsInParams(params).then(Mono.defer(() -> {
            final AclPermission permission = params.getPermission();
            final Set<String> permissionGroups = params.getPermissionGroups();

            if (permission != null && CollectionUtils.isEmpty(permissionGroups)) {
                // There's a permission we want to check, but there's zero permission groups to check against. The
                // resulting query will _always_ be `false`. So returning an early response, without hitting the DB.
                return Mono.just(0L);
            }

            final List<BridgeQuery<T>> criteria =
                    criteriaWithPermission(params.getCriteria(), permissionGroups, permission);

            return postgresOperations.read(em -> PostgresOperations.count(em, genericDomain, criteria));
        }));
    }

    public Mono<Integer> updateExecute(@NonNull QueryAllParams<T> params, @NonNull T resource) {
        return updateExecute(params, buildUpdateFromSparseResource(resource));
    }

    public Mono<Integer> updateExecute(@NonNull QueryAllParams<T> params, @NonNull BridgeUpdate update) {
        if (!CollectionUtils.isEmpty(params.getFields())) {
            // Specifying fields to update doesn't make any sense, so explicitly disallow it.
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, "fields"));
        }

        return ensurePermissionGroupsInParams(params).then(Mono.defer(() -> {
            final AclPermission permission = params.getPermission();
            final Set<String> permissionGroups = params.getPermissionGroups();

            if (permission != null && CollectionUtils.isEmpty(permissionGroups)) {
                // There's a permission we want to check, but there's zero permission groups to check against. The
                // resulting query will _always_ be `false`. So returning an early response, without hitting the DB.
                return Mono.just(0);
            }

            final List<BridgeQuery<T>> criteria =
                    criteriaWithPermission(params.getCriteria(), permissionGroups, permission);
            if (QueryAllParams.Scope.ALL.equals(params.getScope())) {
                return postgresOperations.write(
                        em -> PostgresOperations.update(em, genericDomain, criteria, update, false));
            } else if (QueryAllParams.Scope.FIRST.equals(params.getScope())) {
                return postgresOperations.write(
                        em -> PostgresOperations.update(em, genericDomain, criteria, update, true));
            } else {
                return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, "scope"));
            }
        }));
    }

    public BridgeUpdate buildUpdateFromSparseResource(T resource) {
        // In case the update is not used to update the policies, then set the policies to null to ensure that the
        // existing policies are not overwritten.
        if (CollectionUtils.isEmpty(resource.getPolicies())) {
            resource.setPolicies(null);
        }

        final BridgeUpdate update = Bridge.update();

        ReflectionUtils.doWithFields(
                resource.getClass(),
                field -> {
                    if (field.isAnnotationPresent(Transient.class)
                            || field.isAnnotationPresent(org.springframework.data.annotation.Transient.class)
                            || BaseDomain.Fields.id.equals(field.getName())) {
                        return;
                    }

                    final int modifiers = field.getModifiers();
                    if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers) || Modifier.isTransient(modifiers)) {
                        return;
                    }

                    field.setAccessible(true);
                    final Object value = field.get(resource);
                    if (value != null) {
                        update.set(field.getName(), value);
                    }
                },
                null);

        return update.set(BaseDomain.Fields.updatedAt, Instant.now());
    }

    /**
     * This method will try to ensure that permission groups are present in the params. If they're already there, don't
     * do anything. If not, and if a `permission` is available, then get the permission groups for the current user and
     * permission and fill that into the `params` object.
     * @param params that may have permission groups already, and a permission that can be used to get permission groups otherwise.
     * @return the same `params` object, but with permission groups filled in.
     */
    private Mono<Void> ensurePermissionGroupsInParams(QueryAllParams<T> params) {
        if (params.getPermissionGroups() != null) {
            return Mono.empty();
        }

        return getCurrentUserPermissionGroupsIfRequired(
                        params.getPermission(), params.isIncludeAnonymousUserPermissions())
                .defaultIfEmpty(Collections.emptySet())
                .map(params::permissionGroups)
                .then();
    }

    public Mono<T> setUserPermissionsInObject(T obj) {
        return getCurrentUserPermissionGroups()
                .flatMap(permissionGroups -> setUserPermissionsInObject(obj, permissionGroups));
    }

    public Mono<T> setUserPermissionsInObject(T obj, Set<String> permissionGroups) {
        Set<String> permissions = new HashSet<>();
        obj.setUserPermissions(permissions);

        Set<Policy> existingPolicies = obj.getPolicies();
        final Set<Policy> policies = new HashSet<>(existingPolicies == null ? Set.of() : existingPolicies);
        if (CollectionUtils.isEmpty(policies) || CollectionUtils.isEmpty(permissionGroups)) {
            return Mono.just(obj);
        }

        for (Policy policy : policies) {
            Set<String> policyPermissionGroups = policy.getPermissionGroups();
            if (CollectionUtils.isEmpty(policyPermissionGroups)) {
                continue;
            }
            for (String permissionGroup : permissionGroups) {
                if (policyPermissionGroups.contains(permissionGroup)) {
                    permissions.add(policy.getPermission());
                    break;
                }
            }
        }
        obj.setPolicies(policies);
        return Mono.just(obj);
    }

    /**
     * 1. Get all the user groups associated with the user
     * 2. Get all the permission groups associated with anonymous user
     * 3. Return the set of all the permission groups.
     */
    protected Mono<Set<String>> getAllPermissionGroupsForUser(User user) {

        Mono<User> userMono = Mono.just(user);
        if (user.getOrganizationId() == null) {
            userMono = cacheableRepositoryHelper.getCurrentUserOrganizationId().map(organizationId -> {
                user.setOrganizationId(organizationId);
                return user;
            });
        }

        return userMono.flatMap(userWithOrganization -> Mono.zip(
                        cacheableRepositoryHelper.getPermissionGroupsOfUser(userWithOrganization),
                        getAnonymousUserPermissionGroups()))
                .map(tuple -> {
                    Set<String> permissionGroups = new HashSet<>(tuple.getT1());

                    Set<String> currentUserPermissionGroups = tuple.getT1();
                    Set<String> anonymousUserPermissionGroups = tuple.getT2();

                    permissionGroups.addAll(currentUserPermissionGroups);
                    permissionGroups.addAll(anonymousUserPermissionGroups);

                    return permissionGroups;
                });
    }

    /**
     * 1. Get all the user groups associated with the user
     * 2. Get all the permission groups associated with anonymous user
     * 3. Return the set of all the permission groups.
     */
    protected Mono<Set<String>> getStrictPermissionGroupsForUser(User user) {

        Mono<User> userMono = Mono.just(user);
        if (user.getOrganizationId() == null) {
            userMono = cacheableRepositoryHelper.getCurrentUserOrganizationId().map(organizationId -> {
                user.setOrganizationId(organizationId);
                return user;
            });
        }

        return userMono.flatMap(cacheableRepositoryHelper::getPermissionGroupsOfUser)
                .map(HashSet::new);
    }

    protected Mono<Set<String>> getAnonymousUserPermissionGroups() {
        return cacheableRepositoryHelper.getPermissionGroupsOfAnonymousUser();
    }

    /**
     * Updates a document in the database that matches the provided query and returns the modified document.
     *
     * @param id The unique identifier of the document to be updated.
     * @param updateObj The update object specifying the modifications to be applied to the document.
     * @param permission An optional permission parameter for access control.
     * @return A Mono emitting the updated document after modification.
     *
     * @implNote
     * The update operates at the database level and does not automatically handle encryption or decryption of fields
     * outside the JSON columns. Fork: an UPDATE and a SELECT in one transaction (Mongo's findAndModify).
     */
    public Mono<T> updateAndReturn(String id, BridgeUpdate updateObj, AclPermission permission) {
        final Mono<Set<String>> groupsMono = permission == null
                ? Mono.just(Set.of())
                : getCurrentUserPermissionGroupsIfRequired(permission, true);

        return groupsMono.flatMap(permissionGroups -> {
            final List<BridgeQuery<T>> criteria = new ArrayList<>();
            criteria.add(Bridge.equal(FieldName.ID, id));
            if (permission != null) {
                criteria.addAll(criteriaWithPermission(List.of(), permissionGroups, permission));
            }
            return postgresOperations.write(em -> {
                final int matched = PostgresOperations.update(em, genericDomain, criteria, updateObj, true);
                if (matched == 0) {
                    return null;
                }
                final List<T> rows =
                        PostgresOperations.select(em, genericDomain, List.of(Bridge.equal(FieldName.ID, id)), null, 1, 0);
                return rows.isEmpty() ? null : rows.get(0);
            });
        });
    }

    public Mono<Void> bulkInsert(List<T> domainList) {
        if (CollectionUtils.isEmpty(domainList)) {
            return Mono.empty();
        }

        // Ensure there's no duplicated ID, as the MongoDB version of this method did.
        final Set<String> seenIds = new HashSet<>();
        for (T entity : domainList) {
            if (entity.getId() != null && !seenIds.add(entity.getId())) {
                return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, "id"));
            }
        }

        return postgresOperations
                .write(em -> {
                    for (T entity : domainList) {
                        entity.prePersist();
                        em.persist(entity);
                    }
                    em.flush();
                    domainList.forEach(em::detach);
                    return Boolean.TRUE;
                })
                .then();
    }

    /**
     * Writes the non-null fields of each domain over the stored row (Mongo's bulk {@code $set}).
     */
    public Mono<Void> bulkUpdate(List<T> domainObjects) {
        if (CollectionUtils.isEmpty(domainObjects)) {
            return Mono.empty();
        }

        final Map<String, T> updatesById = new HashMap<>();
        domainObjects.forEach(e -> updatesById.put(e.getId(), e));

        return postgresOperations
                .write(em -> {
                    for (Map.Entry<String, T> entry : updatesById.entrySet()) {
                        final T existing = em.find(genericDomain, entry.getKey());
                        if (existing == null) {
                            continue;
                        }
                        AppsmithBeanUtils.copyNewFieldValuesIntoOldObject(entry.getValue(), existing);
                        existing.preUpdate();
                    }
                    em.flush();
                    em.clear();
                    return Boolean.TRUE;
                })
                .then();
    }
}
