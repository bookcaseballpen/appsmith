package com.appsmith.server.repositories.postgres;

import com.appsmith.server.domains.Application;
import com.appsmith.server.helpers.ce.bridge.Bridge;
import com.appsmith.server.helpers.ce.bridge.BridgeQuery;
import com.appsmith.server.repositories.ApplicationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fork: checks on a JSON array column (the app tags of the catalog) keep MongoDB's matching rules, and the tag filters
 * can use the GIN index of V7.
 */
@SpringBootTest
class JsonArrayQueriesTest {

    @Autowired
    ApplicationRepository applicationRepository;

    @Autowired
    PostgresOperations postgresOperations;

    private void saveApp(String workspaceId, String name, List<String> tags) {
        final Application application = new Application();
        application.setWorkspaceId(workspaceId);
        application.setName(name);
        application.setTags(tags);
        applicationRepository.save(application).block();
    }

    private List<String> namesMatching(String workspaceId, BridgeQuery<Application> criteria) {
        return applicationRepository
                .queryBuilder()
                .criteria(Bridge.<Application>equal(Application.Fields.workspaceId, workspaceId)
                        .and(criteria))
                .all()
                .map(Application::getName)
                .sort()
                .collectList()
                .block();
    }

    private String workspaceWithTaggedApps() {
        final String workspaceId = UUID.randomUUID().toString();
        saveApp(workspaceId, "a-sales", List.of("sales", "north"));
        saveApp(workspaceId, "b-hr", List.of("hr"));
        saveApp(workspaceId, "c-untagged", null);
        saveApp(workspaceId, "d-percent", List.of("50%_off"));
        return workspaceId;
    }

    @Test
    void should_matchAppsHoldingTheTag_when_equalIsCheckedOnTags() {
        // Given
        final String workspaceId = workspaceWithTaggedApps();

        // When
        final List<String> names = namesMatching(workspaceId, Bridge.equal(Application.Fields.tags, "north"));

        // Then
        assertThat(names).containsExactly("a-sales");
    }

    @Test
    void should_matchAppsHoldingAnyTag_when_inIsCheckedOnTags() {
        // Given
        final String workspaceId = workspaceWithTaggedApps();

        // When
        final List<String> names =
                namesMatching(workspaceId, Bridge.in(Application.Fields.tags, List.of("hr", "north")));

        // Then
        assertThat(names).containsExactly("a-sales", "b-hr");
    }

    @Test
    void should_matchAppsHoldingEveryTag_when_equalIsCheckedOncePerTag() {
        // Given
        final String workspaceId = workspaceWithTaggedApps();

        // When
        final List<String> names = namesMatching(
                workspaceId,
                Bridge.<Application>equal(Application.Fields.tags, "sales").equal(Application.Fields.tags, "hr"));

        // Then
        assertThat(names).isEmpty();
    }

    @Test
    void should_includeUntaggedApps_when_notEqualIsCheckedOnTags() {
        // Given
        final String workspaceId = workspaceWithTaggedApps();

        // When
        final List<String> names =
                namesMatching(workspaceId, Bridge.<Application>query().notEqual(Application.Fields.tags, "sales"));

        // Then
        assertThat(names).containsExactly("b-hr", "c-untagged", "d-percent");
    }

    @Test
    void should_matchAnyTagLiterallyIgnoringCase_when_searchIsCheckedOnTags() {
        // Given
        final String workspaceId = workspaceWithTaggedApps();

        // When
        final List<String> byPart = namesMatching(workspaceId, Bridge.searchIgnoreCase(Application.Fields.tags, "NOR"));
        final List<String> byWildcard =
                namesMatching(workspaceId, Bridge.searchIgnoreCase(Application.Fields.tags, "%_"));

        // Then
        assertThat(byPart).containsExactly("a-sales");
        assertThat(byWildcard).containsExactly("d-percent");
    }

    @Test
    void should_useTheTagsIndex_when_tagFilterIsPlanned() {
        // Given
        final SqlRenderer sql = new SqlRenderer(EntityMeta.of(Application.class));
        final String where = sql.where(Bridge.<Application>in(Application.Fields.tags, List.of("hr", "north")));

        // When
        final String plan = postgresOperations.writeBlocking(em -> {
            // Planned as if the table were large; a few test rows would otherwise always be scanned.
            em.createNativeQuery("SET LOCAL enable_seqscan = off").executeUpdate();
            final var query = em.createNativeQuery("EXPLAIN SELECT id FROM application WHERE " + where);
            for (int i = 0; i < sql.params().size(); i++) {
                query.setParameter(i + 1, sql.params().get(i));
            }
            return String.join(
                    "\n", query.getResultList().stream().map(Object::toString).toList());
        });

        // Then
        assertThat(plan).contains("application_tags_idx");
    }
}
