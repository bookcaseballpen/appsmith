package com.appsmith.server.helpers;

import com.appsmith.server.applications.base.ApplicationService;
import com.appsmith.server.helpers.ce.GitPrivateRepoHelperCEImpl;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class GitPrivateRepoHelperImpl extends GitPrivateRepoHelperCEImpl implements GitPrivateRepoHelper {
    public GitPrivateRepoHelperImpl(
            GitCloudServicesUtils gitCloudServicesUtils, ApplicationService applicationService) {
        super(gitCloudServicesUtils, applicationService);
    }

    /**
     * Fork: there is no private-repo limit, on any Git host. Upstream asks cloud services for the limit on connect,
     * import and every commit; this override never does, so those flows work for private repos with the cloud cut.
     */
    @Override
    public Mono<Boolean> isRepoLimitReached(String workspaceId, Boolean isClearCache) {
        return Mono.just(Boolean.FALSE);
    }
}
