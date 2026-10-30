package com.appsmith.server.repositories.postgres;

import com.appsmith.server.domains.Application;
import com.appsmith.server.repositories.ApplicationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Saving an entity whose row is soft-deleted must update that row, as Mongo's save did. With a Hibernate
 * {@code deleted_at IS NULL} filter on the entity, the save could not see the row and inserted it again, failing on
 * the primary key.
 */
@SpringBootTest
class SoftDeletedSaveTest {

    @Autowired
    ApplicationRepository applicationRepository;

    @Autowired
    PostgresOperations postgresOperations;

    @SuppressWarnings("unchecked")
    private List<Object> rowsWithId(String id) {
        return postgresOperations
                .read(em -> (List<Object>) em.createNativeQuery("SELECT name FROM application WHERE id = ?1")
                        .setParameter(1, id)
                        .getResultList())
                .block();
    }

    @Test
    void should_updateTheStoredRow_when_savingAnEntityWhoseRowIsSoftDeleted() {
        // Given
        final Application application = new Application();
        application.setWorkspaceId(UUID.randomUUID().toString());
        application.setName("Archived app");
        final Application saved = applicationRepository.save(application).block();
        saved.setDeletedAt(Instant.now());
        applicationRepository.save(saved).block();

        // When
        saved.setName("Archived app, renamed");
        final Application result = applicationRepository.save(saved).block();

        // Then
        assertThat(result.getId()).isEqualTo(saved.getId());
        assertThat(rowsWithId(saved.getId())).containsExactly("Archived app, renamed");
    }

    @Test
    void should_hideTheRowFromReads_when_entityIsArchived() {
        // Given
        final Application application = new Application();
        application.setWorkspaceId(UUID.randomUUID().toString());
        application.setName("To archive");
        final Application saved = applicationRepository.save(application).block();

        // When
        applicationRepository.archive(saved).block();

        // Then
        StepVerifier.create(applicationRepository.findById(saved.getId())).verifyComplete();
        assertThat(rowsWithId(saved.getId())).hasSize(1);
    }
}
