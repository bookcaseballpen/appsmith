package com.appsmith.server.controllers;

import com.appsmith.server.configurations.CloudServicesConfig;
import com.appsmith.server.constants.Url;
import com.appsmith.server.controllers.ce.SaasControllerCE;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.solutions.AuthenticationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping(Url.SAAS_URL)
public class SaasController extends SaasControllerCE {

    private final CloudServicesConfig cloudServicesConfig;

    public SaasController(AuthenticationService authenticationService, CloudServicesConfig cloudServicesConfig) {
        super(authenticationService, cloudServicesConfig);
        this.cloudServicesConfig = cloudServicesConfig;
    }

    /**
     * Fork: with cloud services disabled the base URL is empty, so the upstream redirect would point at a relative
     * path on this host. Fail with a clear error instead.
     */
    @Override
    @GetMapping("authorize")
    public Mono<Void> redirectForAuthorize(ServerWebExchange exchange, @RequestParam String appsmithToken) {
        if (!cloudServicesConfig.isEnabled()) {
            return Mono.error(
                    new AppsmithException(AppsmithError.CLOUD_SERVICES_ERROR, CloudServicesConfig.DISABLED_MESSAGE));
        }
        return super.redirectForAuthorize(exchange, appsmithToken);
    }
}
