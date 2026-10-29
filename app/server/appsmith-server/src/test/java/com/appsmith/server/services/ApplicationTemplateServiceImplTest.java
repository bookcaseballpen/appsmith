package com.appsmith.server.services;

import com.appsmith.server.applications.base.ApplicationService;
import com.appsmith.server.configurations.CloudServicesConfig;
import com.appsmith.server.dtos.ApplicationImportDTO;
import com.appsmith.server.dtos.ApplicationTemplate;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.exports.internal.ExportService;
import com.appsmith.server.helpers.CacheableTemplateHelper;
import com.appsmith.server.imports.internal.ImportService;
import com.appsmith.server.solutions.ApplicationPermission;
import com.appsmith.server.solutions.ReleaseNotesService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApplicationTemplateServiceImplTest {

    @Mock
    private CloudServicesConfig cloudServicesConfig;

    @Mock
    private ReleaseNotesService releaseNotesService;

    @Mock
    private ImportService importService;

    @Mock
    private ExportService exportService;

    @Mock
    private AnalyticsService analyticsService;

    @Mock
    private UserDataService userDataService;

    @Mock
    private ApplicationService applicationService;

    @Mock
    private ApplicationPermission applicationPermission;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private SessionUserService sessionUserService;

    @Mock
    private CacheableTemplateHelper cacheableTemplateHelper;

    @InjectMocks
    private ApplicationTemplateServiceImpl applicationTemplateService;

    @Test
    void should_returnNoTemplates_when_cloudServicesDisabled() {
        // Given
        when(cloudServicesConfig.isEnabled()).thenReturn(false);

        // When
        Mono<List<ApplicationTemplate>> result = applicationTemplateService.getActiveTemplates(null);

        // Then
        StepVerifier.create(result)
                .assertNext(templates -> assertThat(templates).isEmpty())
                .verifyComplete();
        verifyNoInteractions(cacheableTemplateHelper);
    }

    @Test
    void should_returnEmptyFilterLists_when_cloudServicesDisabled() {
        // Given
        when(cloudServicesConfig.isEnabled()).thenReturn(false);

        // When
        Mono<ApplicationTemplate> result = applicationTemplateService.getFilters();

        // Then
        StepVerifier.create(result)
                .assertNext(filters -> {
                    assertThat(filters.getFunctions()).isEmpty();
                    assertThat(filters.getUseCases()).isEmpty();
                })
                .verifyComplete();
        verifyNoInteractions(cacheableTemplateHelper);
    }

    @Test
    void should_failWithCloudServicesError_when_importingTemplateWithCloudServicesDisabled() {
        // Given
        when(cloudServicesConfig.isEnabled()).thenReturn(false);

        // When
        Mono<ApplicationImportDTO> result =
                applicationTemplateService.importApplicationFromTemplate("templateId", "workspaceId");

        // Then
        StepVerifier.create(result)
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(AppsmithException.class);
                    assertThat(error.getMessage())
                            .isEqualTo(AppsmithError.CLOUD_SERVICES_ERROR.getMessage(
                                    CloudServicesConfig.DISABLED_MESSAGE));
                })
                .verify();
        verifyNoInteractions(cacheableTemplateHelper, importService);
    }
}
