package com.appsmith.server.repositories.ce;

import com.appsmith.server.domains.UserData;
import com.appsmith.server.dtos.RecentlyUsedEntityDTO;
import com.appsmith.server.helpers.ce.bridge.Bridge;
import com.appsmith.server.helpers.ce.bridge.BridgeUpdate;
import com.appsmith.server.projections.UserRecentlyUsedEntitiesProjection;
import com.appsmith.server.repositories.BaseAppsmithRepositoryImpl;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Mono;

import java.util.List;

public class CustomUserDataRepositoryCEImpl extends BaseAppsmithRepositoryImpl<UserData>
        implements CustomUserDataRepositoryCE {

    @Override
    public Mono<Integer> saveReleaseNotesViewedVersion(String userId, String version) {
        return queryBuilder()
                .criteria(Bridge.equal(UserData.Fields.userId, userId))
                .updateFirst(Bridge.update().set(UserData.Fields.releaseNotesViewedVersion, version));
    }

    @Override
    public Mono<Void> removeEntitiesFromRecentlyUsedList(String userId, String workspaceId) {
        BridgeUpdate update = new BridgeUpdate();
        RecentlyUsedEntityDTO recentlyUsedEntityDTO = new RecentlyUsedEntityDTO();
        recentlyUsedEntityDTO.setWorkspaceId(workspaceId);
        update.pull(UserData.Fields.recentlyUsedEntityIds, recentlyUsedEntityDTO);
        return queryBuilder()
                .criteria(Bridge.equal(UserData.Fields.userId, userId))
                .updateFirst(update)
                .then();
    }

    @Override
    public Mono<String> fetchMostRecentlyUsedWorkspaceId(String userId) {
        return queryBuilder()
                .criteria(Bridge.equal(UserData.Fields.userId, userId))
                .one(UserRecentlyUsedEntitiesProjection.class)
                .map(userData -> {
                    final List<RecentlyUsedEntityDTO> recentlyUsedWorkspaceIds = userData.recentlyUsedEntityIds();
                    return CollectionUtils.isEmpty(recentlyUsedWorkspaceIds)
                            ? ""
                            : recentlyUsedWorkspaceIds.get(0).getWorkspaceId();
                });
    }

    @Override
    public Mono<Void> removeApplicationFromFavorites(String applicationId) {
        // MongoDB update query to pull applicationId from all users' favoriteApplicationIds arrays
        BridgeUpdate update = new BridgeUpdate();
        update.pull(UserData.Fields.favoriteApplicationIds, applicationId);
        return queryBuilder().updateAll(update).then();
    }

    @Override
    public Mono<Void> addFavoriteApplicationForUser(String userId, String applicationId) {
        BridgeUpdate update = new BridgeUpdate();
        update.addToSet(UserData.Fields.favoriteApplicationIds, applicationId);
        return queryBuilder()
                .criteria(Bridge.equal(UserData.Fields.userId, userId))
                .updateFirst(update)
                .then();
    }

    @Override
    public Mono<Integer> addFavoriteApplicationForUserIfUnderLimit(String userId, String applicationId, int maxLimit) {
        BridgeUpdate update = new BridgeUpdate();
        update.addToSet(UserData.Fields.favoriteApplicationIds, applicationId);
        // Fork: matches only while the array has fewer than maxLimit elements (Mongo used "field.{N-1}" not existing).
        return queryBuilder()
                .criteria(Bridge.equal(UserData.Fields.userId, userId)
                        .sizeLessThan(UserData.Fields.favoriteApplicationIds, maxLimit))
                .updateFirst(update);
    }

    @Override
    public Mono<Integer> removeFavoriteApplicationForUser(String userId, String applicationId) {
        BridgeUpdate update = new BridgeUpdate();
        update.pull(UserData.Fields.favoriteApplicationIds, applicationId);
        // Only match if the array actually contains the applicationId so that
        // matchedCount == 1 means "removed" and 0 means "was not present".
        return queryBuilder()
                .criteria(Bridge.equal(UserData.Fields.userId, userId)
                        .equal(UserData.Fields.favoriteApplicationIds, applicationId))
                .updateFirst(update);
    }
}
