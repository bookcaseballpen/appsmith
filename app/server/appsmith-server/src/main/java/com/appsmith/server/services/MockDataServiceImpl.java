package com.appsmith.server.services;

import com.appsmith.external.models.Datasource;
import com.appsmith.server.configurations.CloudServicesConfig;
import com.appsmith.server.datasources.base.DatasourceService;
import com.appsmith.server.dtos.MockDataDTO;
import com.appsmith.server.dtos.MockDataSource;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.services.ce.MockDataServiceCEImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Service
public class MockDataServiceImpl extends MockDataServiceCEImpl implements MockDataService {

    private final CloudServicesConfig cloudServicesConfig;

    public MockDataServiceImpl(
            CloudServicesConfig cloudServicesConfig,
            DatasourceService datasourceService,
            AnalyticsService analyticsService,
            SessionUserService sessionUserService) {

        super(cloudServicesConfig, datasourceService, analyticsService, sessionUserService);
        this.cloudServicesConfig = cloudServicesConfig;
    }

    /**
     * Fork: the sample databases are listed by cloud services, so there are none when they are disabled.
     */
    @Override
    public Mono<MockDataDTO> getMockDataSet() {
        if (!cloudServicesConfig.isEnabled()) {
            MockDataDTO mockDataDTO = new MockDataDTO();
            mockDataDTO.setMockdbs(List.of());
            mockDataDTO.setCredentials(List.of());
            return Mono.just(mockDataDTO);
        }
        return super.getMockDataSet();
    }

    @Override
    public Mono<Datasource> createMockDataSet(MockDataSource mockDataSource, String environmentId) {
        if (!cloudServicesConfig.isEnabled()) {
            return Mono.error(
                    new AppsmithException(AppsmithError.CLOUD_SERVICES_ERROR, CloudServicesConfig.DISABLED_MESSAGE));
        }
        return super.createMockDataSet(mockDataSource, environmentId);
    }
}
