package com.appsmith.server.repositories.ce;

import com.appsmith.server.constants.FieldName;
import com.appsmith.server.domains.User;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.helpers.ce.bridge.Bridge;
import com.appsmith.server.helpers.ce.bridge.BridgeUpdate;
import com.appsmith.server.projections.EmailOnly;
import com.appsmith.server.projections.IdOnly;
import com.appsmith.server.repositories.BaseAppsmithRepositoryImpl;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static com.appsmith.server.helpers.ce.bridge.Bridge.notExists;

@Slf4j
public class CustomUserRepositoryCEImpl extends BaseAppsmithRepositoryImpl<User> implements CustomUserRepositoryCE {

    private static final String SUPER_USER_SETUP_TABLE = "super_user_setup_lock";
    private static final String SUPER_USER_SETUP_LOCK_ID = "super-user-setup";

    @Override
    public Mono<User> findByEmailAndOrganizationId(String email, String organizationId) {
        return queryBuilder()
                .criteria(Bridge.equal(User.Fields.email, email).equal(User.Fields.organizationId, organizationId))
                .one();
    }

    /**
     * Fetch minimal information from *a* user document in the database, limit to two documents, filter system generated
     * users.
     * If no documents left return true otherwise return false.
     *
     * @return Boolean, indicated where there exists at least one user in the system or not.
     */
    @Override
    public Mono<Boolean> isUsersEmpty() {
        return queryBuilder()
                .criteria(Bridge.or(
                        notExists(User.Fields.isSystemGenerated), Bridge.isFalse(User.Fields.isSystemGenerated)))
                .limit(1)
                .all(IdOnly.class)
                .count()
                .map(count -> count == 0);
    }

    /**
     * Atomically claims the super user creation slot.
     *
     * Fork: a row with a fixed id in the {@code super_user_setup_lock} table. The INSERT ... ON CONFLICT DO NOTHING
     * is atomic: only the first request inserts the row (one row affected); all later requests insert nothing,
     * which means the slot is already claimed.
     */
    @Override
    public Mono<Boolean> claimSuperUserCreationSlot() {
        return getPostgresOperations()
                .write(em -> em.createNativeQuery(
                                        "INSERT INTO " + SUPER_USER_SETUP_TABLE
                                                + " (id, status, claimed_at) VALUES (?1, 'CLAIMED', ?2) ON CONFLICT (id) DO NOTHING")
                                .setParameter(1, SUPER_USER_SETUP_LOCK_ID)
                                .setParameter(2, Instant.now().toString())
                                .executeUpdate()
                        == 1);
    }

    @Override
    public Mono<Void> releaseSuperUserCreationSlot() {
        return getPostgresOperations()
                .write(em -> em.createNativeQuery("DELETE FROM " + SUPER_USER_SETUP_TABLE + " WHERE id = ?1")
                        .setParameter(1, SUPER_USER_SETUP_LOCK_ID)
                        .executeUpdate())
                .then();
    }

    @Override
    public Flux<String> getSystemGeneratedUserEmails(String organizationId) {
        return queryBuilder()
                .criteria(Bridge.equal(User.Fields.isSystemGenerated, true)
                        .equal(User.Fields.organizationId, organizationId))
                .all(EmailOnly.class)
                .map(EmailOnly::email);
    }

    @Override
    public Mono<Integer> updateById(String id, BridgeUpdate updateObj) {
        if (id == null) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.ID));
        }
        return queryBuilder().byId(id).updateFirst(updateObj);
    }
}
