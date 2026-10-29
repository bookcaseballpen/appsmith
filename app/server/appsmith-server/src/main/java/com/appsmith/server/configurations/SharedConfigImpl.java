package com.appsmith.server.configurations;

import com.appsmith.external.services.SharedConfig;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class SharedConfigImpl implements SharedConfig {

    @Value("${appsmith.codec.max-in-memory-size:10}")
    private int CODEC_SIZE;

    @Value("${appsmith.plugin.response.size.max:5}")
    private float maxPluginResponseSize = 5;

    private final CloudServicesConfig cloudServicesConfig;

    @Override
    public int getCodecSize() {
        return this.CODEC_SIZE * 1024 * 1024;
    }

    @Override
    public int getMaxResponseSize() {
        return (int) (this.maxPluginResponseSize * 1024 * 1024);
    }

    @Override
    public String getRemoteExecutionUrl() {
        return requireCloudServicesBaseUrl() + "/api/v1/actions/execute";
    }

    @Override
    public String getAIProxyBasePathUrl() {
        return requireCloudServicesBaseUrl();
    }

    /**
     * Fork: with cloud services disabled the base URL is empty, and the URLs built from it would be relative paths on
     * this host. Fail with a clear error instead.
     */
    private String requireCloudServicesBaseUrl() {
        if (!cloudServicesConfig.isEnabled()) {
            throw new AppsmithException(AppsmithError.CLOUD_SERVICES_ERROR, CloudServicesConfig.DISABLED_MESSAGE);
        }
        return cloudServicesConfig.getBaseUrl();
    }
}
