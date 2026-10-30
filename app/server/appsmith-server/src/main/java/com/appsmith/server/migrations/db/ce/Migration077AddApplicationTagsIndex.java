package com.appsmith.server.migrations.db.ce;

import com.appsmith.external.models.BaseDomain;
import com.appsmith.server.domains.Application;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

import static com.appsmith.server.migrations.DatabaseChangelog1.dropIndexIfExists;
import static com.appsmith.server.migrations.DatabaseChangelog1.ensureIndexes;
import static com.appsmith.server.migrations.DatabaseChangelog1.makeIndex;

/**
 * Fork: a multikey index for the home page catalog's tag filter and the distinct-tags read
 * ({@code ApplicationCatalogService}). {@code deletedAt} is on it so the soft-delete overlay can use it. The name
 * search and sort use the existing {@code name} + {@code deletedAt} index (Migration037).
 */
@Slf4j
@ChangeUnit(order = "077", id = "fork-add-application-tags-index", author = "")
public class Migration077AddApplicationTagsIndex {

    public static final String APPLICATION_TAGS_DELETED = "application_tags_deleted";

    private final MongoTemplate mongoTemplate;

    public Migration077AddApplicationTagsIndex(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @RollbackExecution
    public void rollbackExecution() {
        // The index is kept; it only speeds up the catalog.
    }

    @Execution
    public void addApplicationTagsIndex() {
        Index index = makeIndex(Application.Fields.tags, BaseDomain.Fields.deletedAt)
                .named(APPLICATION_TAGS_DELETED)
                .background();
        dropIndexIfExists(mongoTemplate, Application.class, APPLICATION_TAGS_DELETED);
        try {
            ensureIndexes(mongoTemplate, Application.class, index);
        } catch (Exception exception) {
            log.error("An error occurred while creating the index : {}", APPLICATION_TAGS_DELETED, exception);
        }
    }
}
