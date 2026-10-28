package com.appsmith.server.services;

import com.appsmith.server.configurations.CloudServicesConfig;
import com.appsmith.server.domains.User;
import com.appsmith.server.services.ce.CacheablePylonHelperCEImpl;
import com.appsmith.server.solutions.ReleaseNotesService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class CacheablePylonHelperImpl extends CacheablePylonHelperCEImpl implements CacheablePylonHelper {
    public CacheablePylonHelperImpl(
            ConfigService configService,
            CloudServicesConfig cloudServicesConfig,
            ReleaseNotesService releaseNotesService) {
        super(configService, cloudServicesConfig, releaseNotesService);
    }

    /**
     * Fork: the Pylon support chat is removed, so no email hash is requested from cloud services and the user
     * profile is returned without one.
     */
    @Override
    public Mono<String> getEmailHash(User user) {
        return Mono.empty();
    }
}
