package com.appsmith.server.services;

import com.appsmith.caching.annotations.Cache;
import com.appsmith.server.configurations.CloudServicesConfig;
import com.appsmith.server.configurations.CommonConfig;
import com.appsmith.server.domains.User;
import com.appsmith.server.dtos.FeaturesRequestDTO;
import com.appsmith.server.dtos.FeaturesResponseDTO;
import com.appsmith.server.featureflags.CachedFlags;
import com.appsmith.server.featureflags.ForkFeatureFlagDefaults;
import com.appsmith.server.repositories.OrganizationRepository;
import com.appsmith.server.services.ce.CacheableFeatureFlagHelperCEImpl;
import com.appsmith.server.solutions.ReleaseNotesService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;

@Component
@Slf4j
public class CacheableFeatureFlagHelperImpl extends CacheableFeatureFlagHelperCEImpl
        implements CacheableFeatureFlagHelper {

    private final CloudServicesConfig cloudServicesConfig;

    public CacheableFeatureFlagHelperImpl(
            OrganizationRepository organizationRepository,
            ConfigService configService,
            CloudServicesConfig cloudServicesConfig,
            CommonConfig commonConfig,
            UserIdentifierService userIdentifierService,
            ReleaseNotesService releaseNotesService) {
        super(
                organizationRepository,
                configService,
                cloudServicesConfig,
                commonConfig,
                userIdentifierService,
                releaseNotesService);
        this.cloudServicesConfig = cloudServicesConfig;
    }

    /**
     * Fork: with cloud services disabled, user flags come from {@link ForkFeatureFlagDefaults}.
     */
    @Cache(cacheName = "featureFlag", key = "{#userIdentifier}")
    @Override
    public Mono<CachedFlags> fetchUserCachedFlags(String userIdentifier, User user) {
        if (!cloudServicesConfig.isEnabled()) {
            return Mono.just(new CachedFlags(ForkFeatureFlagDefaults.get(), Instant.now()));
        }
        return super.fetchUserCachedFlags(userIdentifier, user);
    }

    /**
     * Fork: with cloud services disabled, organization features come from {@link ForkFeatureFlagDefaults}. The CE
     * {@code fetchCachedOrganizationFeatures} calls this method, so its caching stays as upstream.
     */
    @Override
    public Mono<FeaturesResponseDTO> getRemoteFeaturesForOrganization(FeaturesRequestDTO featuresRequestDTO) {
        if (!cloudServicesConfig.isEnabled()) {
            FeaturesResponseDTO featuresResponseDTO = new FeaturesResponseDTO();
            featuresResponseDTO.setFeatures(ForkFeatureFlagDefaults.get());
            return Mono.just(featuresResponseDTO);
        }
        return super.getRemoteFeaturesForOrganization(featuresRequestDTO);
    }
}
