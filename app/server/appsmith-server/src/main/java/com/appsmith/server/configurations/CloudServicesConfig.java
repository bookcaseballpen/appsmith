package com.appsmith.server.configurations;

import com.appsmith.server.configurations.ce.CloudServicesConfigCE;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
@Getter
public class CloudServicesConfig extends CloudServicesConfigCE {

    /**
     * Argument for {@code AppsmithError.CLOUD_SERVICES_ERROR} when a user action needs cloud services.
     */
    public static final String DISABLED_MESSAGE = "(disabled on this instance)";

    /**
     * Fork kill switch for Appsmith cloud services. When disabled (the default), both base URLs are empty, so callers
     * that already treat an empty base URL as "skip" do so, and the remaining callers check {@link #isEnabled()}.
     */
    @Value("${appsmith.cloud_services.enabled:false}")
    private boolean enabled;

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String getBaseUrl() {
        return enabled ? super.getBaseUrl() : "";
    }

    @Override
    public String getBaseUrlWithSignatureVerification() {
        return enabled ? super.getBaseUrlWithSignatureVerification() : "";
    }
}
