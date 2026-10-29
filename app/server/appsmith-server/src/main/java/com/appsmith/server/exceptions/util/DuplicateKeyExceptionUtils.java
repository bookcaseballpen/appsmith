package com.appsmith.server.exceptions.util;

import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class DuplicateKeyExceptionUtils {
    private static final Pattern pattern = Pattern.compile("dup key: \\{ .*:(.*)}'");

    // Fork: PostgreSQL's detail line, e.g. Key (workspace_id, name, deleted_at)=(ws1, My App, null) already exists.
    private static final Pattern postgresPattern = Pattern.compile("Key \\((.*)\\)=\\((.*)\\) already exists");

    public static String extractConflictingObjectName(String duplicateKeyErrorMessage) {
        Matcher matcher = pattern.matcher(duplicateKeyErrorMessage);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        Matcher postgresMatcher = postgresPattern.matcher(duplicateKeyErrorMessage);
        if (postgresMatcher.find()) {
            return "\"" + postgresKeyValue(postgresMatcher.group(1), postgresMatcher.group(2)) + "\"";
        }
        log.warn(
                "DuplicateKeyException regex needs attention. It's unable to extract object name from the error message. Possible reason: the underlying library may have changed the format of the error message.");
        /*
           [Fallback strategy]
           AppsmithError.DUPLICATE_KEY has a placeholder where it expects the name of the object that conflicts with the existing names.
           In case the execution reaches here we don't want to render `null` rather the string returned as below will yet make the message look good.
        */
        return "that you provided";
    }

    /**
     * The value of the key's {@code name} column, or of its last column when it has none (as Mongo's message shows the
     * last field). PostgreSQL joins the values with ", " unquoted, so a name holding ", " splits into more parts than
     * there are columns: the extra parts belong to the name.
     */
    private static String postgresKeyValue(String columnList, String valueList) {
        List<String> columns = Arrays.asList(columnList.split(", "));
        String[] values = valueList.split(", ", -1);
        int extra = Math.max(0, values.length - columns.size());
        int index = columns.indexOf("name");
        if (index < 0) {
            return values[values.length - 1];
        }
        return String.join(", ", Arrays.copyOfRange(values, index, Math.min(values.length, index + 1 + extra)));
    }
}
