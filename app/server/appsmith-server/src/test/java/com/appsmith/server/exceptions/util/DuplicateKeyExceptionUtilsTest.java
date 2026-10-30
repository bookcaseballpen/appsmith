package com.appsmith.server.exceptions.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateKeyExceptionUtilsTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '`',
            value = {
                // Mongo
                "E11000 duplicate key error collection: appsmith.actionCollection index: unpublishedCollection.name_1"
                        + " dup key: { unpublishedCollection.name: \"MyJSObject\" }', details={}}.|\"MyJSObject\"",
                // PostgreSQL: the name column of a compound key
                "ERROR: duplicate key value violates unique constraint \"workspace_datasource_deleted_compound_index\""
                        + "  Detail: Key (workspace_id, name, deleted_at)=(ws1, Users DB, null) already exists.|\"Users DB\"",
                // PostgreSQL: a name holding the separator
                "Detail: Key (workspace_id, name, deleted_at)=(ws1, Sales, North, null) already exists.|\"Sales, North\"",
                // PostgreSQL: no name column, the last value
                "Detail: Key (email, organization_id)=(a@example.com, org1) already exists.|\"org1\"",
                // Neither format
                "something else|that you provided",
            })
    void should_extractTheConflictingName_when_messageComesFromEitherDatabase(String message, String expected) {
        // Given / When
        String name = DuplicateKeyExceptionUtils.extractConflictingObjectName(message);

        // Then
        assertThat(name).isEqualTo(expected);
    }
}
