package com.appsmith.server.repositories.postgres;

import com.appsmith.external.models.Datasource;
import com.appsmith.server.domains.Application;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.util.DuplicateKeyExceptionUtils;
import com.appsmith.server.repositories.ApplicationRepository;
import com.appsmith.server.repositories.DatasourceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class UniqueNameIndexesTest {

    @Autowired
    ApplicationRepository applicationRepository;

    @Autowired
    DatasourceRepository datasourceRepository;

    private static Application application(String workspaceId, String name) {
        final Application application = new Application();
        application.setWorkspaceId(workspaceId);
        application.setName(name);
        return application;
    }

    private static Datasource datasource(String workspaceId, String name) {
        final Datasource datasource = new Datasource();
        datasource.setWorkspaceId(workspaceId);
        datasource.setName(name);
        return datasource;
    }

    @Test
    void should_failWithDuplicateKeyNamingTheIndex_when_applicationNameIsTakenInWorkspace() {
        // Given
        final String workspaceId = UUID.randomUUID().toString();
        applicationRepository.save(application(workspaceId, "App")).block();

        // When
        final Mono<Application> result = applicationRepository.save(application(workspaceId, "App"));

        // Then
        StepVerifier.create(result)
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(DuplicateKeyException.class)
                        .hasMessageContaining("workspace_app_deleted_gitApplicationMetadata"))
                .verify();
    }

    @Test
    void should_nameTheConflictingApplicationInTheErrorMessage_when_applicationNameIsTaken() {
        // Given
        final String workspaceId = UUID.randomUUID().toString();
        applicationRepository.save(application(workspaceId, "Sales, North")).block();

        // When
        final Mono<Application> result = applicationRepository.save(application(workspaceId, "Sales, North"));

        // Then
        StepVerifier.create(result)
                .expectErrorSatisfies(error -> assertThat(AppsmithError.DUPLICATE_KEY.getMessage(
                                DuplicateKeyExceptionUtils.extractConflictingObjectName(
                                        error.getCause().getMessage())))
                        .startsWith("Duplicate key error: An object with the name \"Sales, North\" already exists."))
                .verify();
    }

    @Test
    void should_leaveIdUnset_when_insertFailsOnDuplicateName() {
        // Given
        final String workspaceId = UUID.randomUUID().toString();
        datasourceRepository.save(datasource(workspaceId, "DS")).block();
        final Datasource duplicate = datasource(workspaceId, "DS");

        // When
        final Mono<Datasource> result = datasourceRepository.save(duplicate);

        // Then
        StepVerifier.create(result).expectError(DuplicateKeyException.class).verify();
        assertThat(duplicate.getId()).isNull();
    }

    @Test
    void should_saveBoth_when_sameApplicationNameIsInAnotherWorkspaceOrDeleted() {
        // Given
        final String workspaceId = UUID.randomUUID().toString();
        applicationRepository.save(application(workspaceId, "App")).block();
        final Application deleted = application(workspaceId, "App");
        deleted.setDeletedAt(Instant.now());

        // When
        final Mono<Long> result = applicationRepository
                .save(application(UUID.randomUUID().toString(), "App"))
                .then(applicationRepository.save(deleted))
                .thenReturn(1L);

        // Then
        StepVerifier.create(result).expectNext(1L).verifyComplete();
    }

    @Test
    void should_failWithDuplicateKeyNamingTheIndex_when_datasourceNameIsTakenInWorkspace() {
        // Given
        final String workspaceId = UUID.randomUUID().toString();
        datasourceRepository.save(datasource(workspaceId, "DS")).block();

        // When
        final Mono<Datasource> result = datasourceRepository.save(datasource(workspaceId, "DS"));

        // Then
        StepVerifier.create(result)
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(DuplicateKeyException.class)
                        .hasMessageContaining("workspace_datasource_deleted_compound_index"))
                .verify();
    }
}
