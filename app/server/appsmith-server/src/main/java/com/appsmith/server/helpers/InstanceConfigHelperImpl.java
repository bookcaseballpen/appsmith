package com.appsmith.server.helpers;

import com.appsmith.external.services.RTSCaller;
import com.appsmith.server.configurations.CloudServicesConfig;
import com.appsmith.server.configurations.CommonConfig;
import com.appsmith.server.domains.Config;
import com.appsmith.server.helpers.ce.InstanceConfigHelperCEImpl;
import com.appsmith.server.repositories.postgres.PostgresOperations;
import com.appsmith.server.services.AnalyticsService;
import com.appsmith.server.services.ConfigService;
import com.appsmith.server.services.FeatureFlagService;
import com.appsmith.server.services.OrganizationService;
import com.appsmith.server.solutions.ReleaseNotesService;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class InstanceConfigHelperImpl extends InstanceConfigHelperCEImpl implements InstanceConfigHelper {

    private final CloudServicesConfig cloudServicesConfig;

    public InstanceConfigHelperImpl(
            ConfigService configService,
            CloudServicesConfig cloudServicesConfig,
            CommonConfig commonConfig,
            ApplicationContext applicationContext,
            PostgresOperations postgresOperations,
            FeatureFlagService featureFlagService,
            AnalyticsService analyticsService,
            NetworkUtils networkUtils,
            ReleaseNotesService releaseNotesService,
            RTSCaller rtsCaller,
            OrganizationService organizationService) {
        super(
                configService,
                cloudServicesConfig,
                commonConfig,
                applicationContext,
                postgresOperations,
                featureFlagService,
                analyticsService,
                networkUtils,
                releaseNotesService,
                rtsCaller,
                organizationService);
        this.cloudServicesConfig = cloudServicesConfig;
    }

    /**
     * Fork: the instance is not registered with cloud services when they are disabled. Completing empty lets the
     * startup chain in {@code InstanceConfig} move on to the RTS health check without logging an error.
     */
    @Override
    public Mono<? extends Config> registerInstance() {
        if (!cloudServicesConfig.isEnabled()) {
            return Mono.empty();
        }
        return super.registerInstance();
    }
}
