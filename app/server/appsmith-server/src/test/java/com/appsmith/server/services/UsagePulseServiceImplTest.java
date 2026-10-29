package com.appsmith.server.services;

import com.appsmith.server.configurations.CommonConfig;
import com.appsmith.server.domains.UsagePulse;
import com.appsmith.server.dtos.UsagePulseDTO;
import com.appsmith.server.repositories.UsagePulseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class UsagePulseServiceImplTest {

    @Mock
    private UsagePulseRepository repository;

    @Mock
    private SessionUserService sessionUserService;

    @Mock
    private UserService userService;

    @Mock
    private OrganizationService organizationService;

    @Mock
    private ConfigService configService;

    @Mock
    private CommonConfig commonConfig;

    @InjectMocks
    private UsagePulseServiceImpl usagePulseService;

    @Test
    void should_notRecordPulse_when_pulseIsSent() {
        // Given
        UsagePulseDTO usagePulseDTO = new UsagePulseDTO();
        usagePulseDTO.setViewMode(true);

        // When
        Mono<UsagePulse> result = usagePulseService.createPulse(usagePulseDTO);

        // Then
        StepVerifier.create(result).verifyComplete();
        verifyNoInteractions(repository, sessionUserService, userService, organizationService, configService);
    }
}
