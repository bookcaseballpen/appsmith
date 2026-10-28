package com.appsmith.server.configurations;

import org.springframework.context.annotation.Configuration;

/**
 * Fork: Segment is removed. This shim stays because CE classes (scheduled pings, release notes) still read the CE
 * key; it is always empty, so those code paths treat telemetry as unconfigured.
 */
@Configuration
public class SegmentConfig {

    public String getCeKey() {
        return "";
    }
}
