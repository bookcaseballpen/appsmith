package com.appsmith.server.helpers;

import com.appsmith.server.configurations.CloudServicesConfig;
import com.appsmith.server.helpers.ce.NetworkUtilsCE;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class NetworkUtils extends NetworkUtilsCE {

    private final CloudServicesConfig cloudServicesConfig;

    public NetworkUtils(CloudServicesConfig cloudServicesConfig) {
        super(cloudServicesConfig);
        this.cloudServicesConfig = cloudServicesConfig;
    }

    /**
     * Fork: the external IP is looked up through cloud services, so it is reported as unknown when they are disabled.
     */
    @Override
    public Mono<String> getExternalAddress() {
        if (!cloudServicesConfig.isEnabled()) {
            return Mono.just(FALLBACK_IP);
        }
        return super.getExternalAddress();
    }
}
