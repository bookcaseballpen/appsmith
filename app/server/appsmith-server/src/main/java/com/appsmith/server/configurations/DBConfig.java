package com.appsmith.server.configurations;

import com.appsmith.server.repositories.postgres.EnableAppsmithRepositories;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.ReactiveTransaction;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.reactive.TransactionCallback;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
     * Each repository call is now its own JDBC transaction; this operator runs the work as it is, without a
     * surrounding transaction. Making those flows atomic again is left for a later milestone (see FORK.md).
     */
    @Bean
    public TransactionalOperator transactionalOperator() {
        return new TransactionalOperator() {
            @Override
            public <T> Flux<T> transactional(Flux<T> flux) {
                return flux;
            }

            @Override
            public <T> Mono<T> transactional(Mono<T> mono) {
                return mono;
            }

            @Override
            public <T> Flux<T> execute(TransactionCallback<T> action) throws TransactionException {
                return Flux.from(action.doInTransaction(new ReactiveTransaction() {
                    @Override
                    public boolean isNewTransaction() {
                        return false;
                    }

                    @Override
                    public void setRollbackOnly() {}

                    @Override
                    public boolean isRollbackOnly() {
                        return false;
                    }

                    @Override
                    public boolean isCompleted() {
                        return false;
                    }
                }));
            }
        };
    }
}
