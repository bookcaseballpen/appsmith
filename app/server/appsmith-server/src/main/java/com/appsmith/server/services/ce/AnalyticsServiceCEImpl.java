package com.appsmith.server.services.ce;

import com.appsmith.external.constants.AnalyticsEvents;
import com.appsmith.external.models.BaseDomain;
import com.appsmith.server.domains.User;
import com.appsmith.server.domains.UserData;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Fork: Segment analytics is removed. This is an inert shim kept so the many call sites across the server stay
 * untouched: nothing is collected or sent, {@link #isActive()} is always false and every method returns its input.
 */
@Slf4j
public class AnalyticsServiceCEImpl implements AnalyticsServiceCE {

    @Override
    public boolean isActive() {
        return false;
    }

    @Override
    public Mono<User> identifyUser(User user, UserData userData) {
        return Mono.just(user);
    }

    @Override
    public Mono<User> identifyUser(User user, UserData userData, String recentlyUsedWorkspaceId) {
        return Mono.just(user);
    }

    @Override
    public void identifyInstance(
            String instanceId,
            String proficiency,
            String useCase,
            String adminEmail,
            String adminFullName,
            String ip) {}

    @Override
    public Mono<Void> sendEvent(String event, String userId, Map<String, ?> properties) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> sendEvent(String event, String userId, Map<String, ?> properties, boolean hashUserId) {
        return Mono.empty();
    }

    @Override
    public <T extends BaseDomain> Mono<T> sendObjectEvent(AnalyticsEvents event, T object) {
        return Mono.just(object);
    }

    @Override
    public <T> Mono<T> sendObjectEvent(AnalyticsEvents event, T object, Map<String, Object> extraProperties) {
        return Mono.just(object);
    }

    @Override
    public <T extends BaseDomain> Mono<T> sendCreateEvent(T object, Map<String, Object> extraProperties) {
        return Mono.just(object);
    }

    @Override
    public <T extends BaseDomain> Mono<T> sendCreateEvent(T object) {
        return Mono.just(object);
    }

    @Override
    public <T extends BaseDomain> Mono<T> sendUpdateEvent(T object, Map<String, Object> extraProperties) {
        return Mono.just(object);
    }

    @Override
    public <T extends BaseDomain> Mono<T> sendUpdateEvent(T object) {
        return Mono.just(object);
    }

    @Override
    public <T extends BaseDomain> Mono<T> sendDeleteEvent(T object, Map<String, Object> extraProperties) {
        return Mono.just(object);
    }

    @Override
    public <T extends BaseDomain> Mono<T> sendArchiveEvent(T object, Map<String, Object> extraProperties) {
        return Mono.just(object);
    }

    @Override
    public <T extends BaseDomain> Mono<T> sendDeleteEvent(T object) {
        return Mono.just(object);
    }
}
