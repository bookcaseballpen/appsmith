package com.appsmith.server.services;

import com.appsmith.server.editorlock.EditorReadOnlySetting;
import com.appsmith.server.helpers.FeatureFlagMigrationHelper;
import com.appsmith.server.services.ce.FeatureFlagServiceCEImpl;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@Component
public class FeatureFlagServiceImpl extends FeatureFlagServiceCEImpl implements FeatureFlagService {

    private final EditorReadOnlySetting editorReadOnlySetting;

    public FeatureFlagServiceImpl(
            SessionUserService sessionUserService,
            OrganizationService organizationService,
            UserIdentifierService userIdentifierService,
            CacheableFeatureFlagHelper cacheableFeatureFlagHelper,
            FeatureFlagMigrationHelper featureFlagMigrationHelper,
            EditorReadOnlySetting editorReadOnlySetting) {
        super(
                sessionUserService,
                organizationService,
                userIdentifierService,
                cacheableFeatureFlagHelper,
                featureFlagMigrationHelper);
        this.editorReadOnlySetting = editorReadOnlySetting;
    }

    /**
     * Fork: adds {@link EditorReadOnlySetting#FEATURE_FLAG}, so the client hides the editing UI when the server
     * refuses editor changes. Set here, after the cached and remote flags, so neither can override it.
     */
    @Override
    public Mono<Map<String, Boolean>> getAllFeatureFlagsForUser() {
        return super.getAllFeatureFlagsForUser().map(flags -> {
            Map<String, Boolean> withReadOnly = new HashMap<>(flags);
            withReadOnly.put(EditorReadOnlySetting.FEATURE_FLAG, editorReadOnlySetting.isEnabled());
            return withReadOnly;
        });
    }
}
