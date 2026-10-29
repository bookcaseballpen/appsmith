package com.appsmith.server.editorlock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fork: whether the editor is read-only ({@code APPSMITH_EDITOR_READ_ONLY}, default true). Apps are built in the
 * projects repo and reach the server through app import or the MCP service; the editor only runs and debugs them.
 * Read by {@link EditorReadOnlyWebFilter} and sent to the client as the {@link #FEATURE_FLAG} feature flag.
 */
@Component
public class EditorReadOnlySetting {

    public static final String FEATURE_FLAG = "fork_editor_read_only";

    private final boolean enabled;

    public EditorReadOnlySetting(@Value("${appsmith.editor.read-only:true}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
