package com.appsmith.server.configurations;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Class to configure beans based on DB url. This could have been implemented in {@link DBConfig} but extracted to
 * seperate class as we were facing cyclical dependency issue with the other approach.
 * <p>
 * Fork: PostgreSQL is the only internal database. {@code APPSMITH_DB_URL} must be
 * {@code postgresql://user:password@host:port/database}; a {@code mongodb://} URL stops the server with an error.
 */
@Configuration
@Slf4j
public class CommonDBConfig {

    @Value("${appsmith.db.url}")
    private String appsmithDbUrl;

    static final String JDBC_PREFIX = "jdbc:";

    @Bean
    @Primary
    public DataSourceProperties configurePostgresDB() {
        if (!StringUtils.hasText(appsmithDbUrl) || !appsmithDbUrl.startsWith("postgresql://")) {
            throw new IllegalStateException(
                    "APPSMITH_DB_URL must be a PostgreSQL URL, postgresql://{username}:{password}@{host}:{port}/{db_name}."
                            + " This build of the server does not support MongoDB.");
        }
        log.info("Found PostgreSQL uri configuring now");
        return extractJdbcProperties(appsmithDbUrl);
    }

    /**
     * Method to extract Jdbc props from the given DB URL
     * Expected DB URL: postgresql://{username}:{password}@localhost:{port}/{db_name}
     */
    public DataSourceProperties extractJdbcProperties(String dbUrl) {
        DataSourceProperties ds = new DataSourceProperties();
        try {
            URI uri = new URI(dbUrl);
            if (!StringUtils.hasLength(uri.getHost())) {
                String errorString = String.format(
                        "Malformed DB URL! Expected format: postgresql://{username}:{password}@localhost:{port}/{db_name}, provided url is %s",
                        dbUrl.replaceAll("//[^@/]*@", "//***@"));
                throw new IllegalArgumentException(errorString);
            }
            String userInfo = uri.getRawUserInfo();
            if (StringUtils.hasLength(userInfo)) {
                String[] userDetails = userInfo.split(":", 2);
                ds.setUsername(URLDecoder.decode(userDetails[0], StandardCharsets.UTF_8));
                if (userDetails.length > 1) {
                    ds.setPassword(URLDecoder.decode(userDetails[1], StandardCharsets.UTF_8));
                }
            }
            // If the port is not mentioned, default it to the standard PostgreSQL port 5432
            int port = uri.getPort() == -1 ? 5432 : uri.getPort();

            // Check if the URL already has query parameters
            String query = uri.getQuery();
            String updatedUrl;
            if (StringUtils.hasLength(query)) {
                // Append currentSchema=appsmith if there are already parameters
                updatedUrl = String.format(
                        "%s%s://%s:%s%s?%s&currentSchema=appsmith",
                        JDBC_PREFIX, uri.getScheme(), uri.getHost(), port, uri.getPath(), query);
            } else {
                // No parameters, just append currentSchema
                updatedUrl = String.format(
                        "%s%s://%s:%s%s?currentSchema=appsmith",
                        JDBC_PREFIX, uri.getScheme(), uri.getHost(), port, uri.getPath());
            }
            ds.setUrl(updatedUrl);
            return ds;
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
