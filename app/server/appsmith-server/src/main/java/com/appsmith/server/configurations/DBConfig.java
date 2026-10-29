package com.appsmith.server.configurations;

import com.appsmith.server.repositories.postgres.EnableAppsmithRepositories;
import com.appsmith.server.repositories.postgres.PostgresTransactionalOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

/**
 * Fork: the internal database is PostgreSQL (replaces MongoConfig). Entities are mapped with JPA/Hibernate, the
 * schema is owned by Flyway ({@code db/migration/postgres}), and the reactive repository interfaces are implemented
 * by {@link EnableAppsmithRepositories}. See FORK.md, "PostgreSQL".
 */
@Slf4j
@Configuration
@EntityScan(basePackages = {"com.appsmith.server.domains", "com.appsmith.external.models"})
@EnableAppsmithRepositories(basePackages = "com.appsmith.server.repositories")
public class DBConfig {

    /**
     * The Mongo version ran some multi-document writes (imports, git operations) in a reactive Mongo transaction.
     * This operator runs them in one JPA transaction (see {@link PostgresTransactionalOperator}).
     */
    @Bean
    public TransactionalOperator transactionalOperator(PlatformTransactionManager transactionManager) {
        return new PostgresTransactionalOperator(transactionManager);
    }
}
