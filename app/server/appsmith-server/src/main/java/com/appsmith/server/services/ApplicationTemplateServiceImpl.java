package com.appsmith.server.services;

import com.appsmith.server.applications.base.ApplicationService;
import com.appsmith.server.configurations.CloudServicesConfig;
import com.appsmith.server.domains.Application;
import com.appsmith.server.dtos.ApplicationJson;
import com.appsmith.server.dtos.ApplicationTemplate;
import com.appsmith.server.dtos.TemplateDTO;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.exports.internal.ExportService;
import com.appsmith.server.helpers.CacheableTemplateHelper;
import com.appsmith.server.imports.internal.ImportService;
import com.appsmith.server.services.ce.ApplicationTemplateServiceCEImpl;
import com.appsmith.server.solutions.ApplicationPermission;
import com.appsmith.server.solutions.ReleaseNotesService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Fork: templates live in Appsmith cloud services. When they are disabled, listings are empty and any action that
 * needs a template (details, fork, merge, building blocks, publish) fails with a cloud-services error, without a call.
 */
@Service
@Slf4j
public class ApplicationTemplateServiceImpl extends ApplicationTemplateServiceCEImpl
        implements ApplicationTemplateService {

    private final CloudServicesConfig cloudServicesConfig;

    public ApplicationTemplateServiceImpl(
            CloudServicesConfig cloudServicesConfig,
            ReleaseNotesService releaseNotesService,
            ImportService importService,
            ExportService exportService,
            AnalyticsService analyticsService,
            UserDataService userDataService,
            ApplicationService applicationService,
            ApplicationPermission applicationPermission,
            ObjectMapper objectMapper,
            SessionUserService sessionUserService,
            CacheableTemplateHelper cacheableTemplateHelper) {
        super(
                cloudServicesConfig,
                releaseNotesService,
                importService,
                exportService,
                analyticsService,
                applicationService,
                applicationPermission,
                objectMapper,
                sessionUserService,
                cacheableTemplateHelper);
        this.cloudServicesConfig = cloudServicesConfig;
    }

    private static <T> Mono<T> cloudServicesDisabled() {
        return Mono.error(
                new AppsmithException(AppsmithError.CLOUD_SERVICES_ERROR, CloudServicesConfig.DISABLED_MESSAGE));
    }

    @Override
    public Mono<List<ApplicationTemplate>> getActiveTemplates(List<String> templateIds) {
        if (!cloudServicesConfig.isEnabled()) {
            return Mono.just(List.of());
        }
        return super.getActiveTemplates(templateIds);
    }

    @Override
    public Flux<ApplicationTemplate> getSimilarTemplates(String templateId, MultiValueMap<String, String> params) {
        if (!cloudServicesConfig.isEnabled()) {
            return Flux.empty();
        }
        return super.getSimilarTemplates(templateId, params);
    }

    @Override
    public Mono<ApplicationTemplate> getFilters() {
        if (!cloudServicesConfig.isEnabled()) {
            // Empty lists rather than nulls, so the client reads a complete, empty filter set
            ApplicationTemplate filters = new ApplicationTemplate();
            filters.setFunctions(List.of());
            filters.setUseCases(List.of());
            return Mono.just(filters);
        }
        return super.getFilters();
    }

    @Override
    public Mono<ApplicationTemplate> getTemplateDetails(String templateId) {
        if (!cloudServicesConfig.isEnabled()) {
            return cloudServicesDisabled();
        }
        return super.getTemplateDetails(templateId);
    }

    /**
     * Also covers importing, merging and building blocks, which all read the template JSON through this method.
     */
    @Override
    public Mono<ApplicationJson> getApplicationJsonFromTemplate(String templateId) {
        if (!cloudServicesConfig.isEnabled()) {
            return cloudServicesDisabled();
        }
        return super.getApplicationJsonFromTemplate(templateId);
    }

    @Override
    public Mono<Application> publishAsCommunityTemplate(TemplateDTO resource) {
        if (!cloudServicesConfig.isEnabled()) {
            return cloudServicesDisabled();
        }
        return super.publishAsCommunityTemplate(resource);
    }

    @Override
    public Mono<Boolean> publishAppsmithTemplate(TemplateDTO resource) {
        if (!cloudServicesConfig.isEnabled()) {
            return cloudServicesDisabled();
        }
        return super.publishAppsmithTemplate(resource);
    }
}
