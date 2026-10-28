package com.appsmith.server.services;

import com.appsmith.server.configurations.CommonConfig;
import com.appsmith.server.domains.UsagePulse;
import com.appsmith.server.dtos.UsagePulseDTO;
import com.appsmith.server.repositories.UsagePulseRepository;
import com.appsmith.server.services.ce.UsagePulseServiceCEImpl;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class UsagePulseServiceImpl extends UsagePulseServiceCEImpl implements UsagePulseService {

    public UsagePulseServiceImpl(
            UsagePulseRepository repository,
            SessionUserService sessionUserService,
            UserService userService,
            OrganizationService organizationService,
            ConfigService configService,
            CommonConfig commonConfig) {
        super(repository, sessionUserService, userService, organizationService, configService, commonConfig);
    }

    /**
     * Fork: usage pulses are not recorded. The client no longer sends them; this keeps the endpoint inert for old
     * clients.
     */
    @Override
    public Mono<UsagePulse> createPulse(UsagePulseDTO usagePulseDTO) {
        return Mono.empty();
    }
}
