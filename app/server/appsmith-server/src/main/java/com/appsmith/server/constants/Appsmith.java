package com.appsmith.server.constants;

public class Appsmith {
    public static final String APPSMITH_REGISTERED = "appsmith_registered";
    public static final String INSTANCE_SCHEMA_VERSION = "schemaVersion";
    // We default the origin header to the production deployment of the client's URL
    public static final String DEFAULT_ORIGIN_HEADER = "https://app.appsmith.com";
    // Fork: a fresh install is named after the fork's product (Brand).
    public static final String DEFAULT_INSTANCE_NAME = Brand.PRODUCT_NAME;
}
