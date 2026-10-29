package com.appsmith.server.solutions;

import com.appsmith.external.models.OAuth2ResponseDTO;
import com.appsmith.server.configurations.CloudServicesConfig;
import com.appsmith.server.datasources.base.DatasourceService;
import com.appsmith.server.datasourcestorages.base.DatasourceStorageService;
import com.appsmith.server.dtos.RequestAppsmithTokenDTO;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.helpers.InstanceConfigHelper;
import com.appsmith.server.helpers.PluginExecutorHelper;
import com.appsmith.server.helpers.RedirectHelper;
import com.appsmith.server.newpages.base.NewPageService;
import com.appsmith.server.plugins.base.PluginService;
import com.appsmith.server.services.ConfigService;
import com.appsmith.server.solutions.ce.AuthenticationServiceCEImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Fork: OAuth for SaaS plugins (for example Google Sheets) is brokered by Appsmith cloud services, so authorizing
 * fails with a cloud-services error, without a call, when they are disabled. Token refresh is left to the CE code: it
 * returns empty for generic OAuth2, and for SaaS plugins the empty base URL makes it fail before any request is sent.
 * Generic OAuth2 for REST APIs does not use the cloud and is unchanged.
 */
@Service
@Slf4j
public class AuthenticationServiceImpl extends AuthenticationServiceCEImpl implements AuthenticationService {

    private final CloudServicesConfig cloudServicesConfig;

    public AuthenticationServiceImpl(
            DatasourceService datasourceService,
            PluginService pluginService,
            RedirectHelper redirectHelper,
            NewPageService newPageService,
            CloudServicesConfig cloudServicesConfig,
            ConfigService configService,
            DatasourcePermission datasourcePermission,
            PagePermission pagePermission,
            PluginExecutorHelper pluginExecutorHelper,
            DatasourceStorageService datasourceStorageService,
            InstanceConfigHelper instanceConfigHelper) {
        super(
                datasourceService,
                pluginService,
                redirectHelper,
                newPageService,
                cloudServicesConfig,
                configService,
                datasourcePermission,
                pagePermission,
                pluginExecutorHelper,
                datasourceStorageService,
                instanceConfigHelper);
        this.cloudServicesConfig = cloudServicesConfig;
    }

    private static <T> Mono<T> cloudServicesDisabled() {
        return Mono.error(
                new AppsmithException(AppsmithError.CLOUD_SERVICES_ERROR, CloudServicesConfig.DISABLED_MESSAGE));
    }

    @Override
    public Mono<String> getAppsmithToken(
            String datasourceId,
            String environmentId,
            RequestAppsmithTokenDTO requestAppsmithTokenDTO,
            HttpHeaders headers,
            String importForGit) {
        if (!cloudServicesConfig.isEnabled()) {
            return cloudServicesDisabled();
        }
        return super.getAppsmithToken(datasourceId, environmentId, requestAppsmithTokenDTO, headers, importForGit);
    }

    @Override
    public Mono<OAuth2ResponseDTO> getAccessTokenFromCloud(
            String datasourceId, String environmentId, String appsmithToken) {
        if (!cloudServicesConfig.isEnabled()) {
            return cloudServicesDisabled();
        }
        return super.getAccessTokenFromCloud(datasourceId, environmentId, appsmithToken);
    }
}
