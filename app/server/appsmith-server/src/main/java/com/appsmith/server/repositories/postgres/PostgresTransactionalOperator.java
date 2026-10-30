package com.appsmith.server.repositories.postgres;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.ReactiveTransaction;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.reactive.TransactionCallback;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Fork: makes the multi-step writes that ran in a reactive Mongo transaction (imports, git operations, MCP tokens)
 * atomic on PostgreSQL. The wrapped publisher runs with a {@link PostgresTransaction} in its Reactor context; every
 * repository call inside it joins that JPA transaction. It commits when the publisher completes and rolls back when it
 * fails or is cancelled. A publisher that is already inside a transaction joins it.
 */
@Slf4j
public class PostgresTransactionalOperator implements TransactionalOperator {

    private final PlatformTransactionManager transactionManager;
    private final AtomicLong counter = new AtomicLong();

    public PostgresTransactionalOperator(PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    @Override
    public <T> Mono<T> transactional(Mono<T> mono) {
        return Mono.deferContextual(context -> {
            if (context.hasKey(PostgresTransaction.CONTEXT_KEY)) {
                return mono;
            }
            return Mono.usingWhen(
                    begin(),
                    tx -> mono.contextWrite(c -> c.put(PostgresTransaction.CONTEXT_KEY, tx)),
                    this::commit,
                    (tx, error) -> rollback(tx),
                    this::rollback);
        });
    }

    @Override
    public <T> Flux<T> transactional(Flux<T> flux) {
        return Flux.deferContextual(context -> {
            if (context.hasKey(PostgresTransaction.CONTEXT_KEY)) {
                return flux;
            }
            return Flux.usingWhen(
                    begin(),
                    tx -> flux.contextWrite(c -> c.put(PostgresTransaction.CONTEXT_KEY, tx)),
                    this::commit,
                    (tx, error) -> rollback(tx),
                    this::rollback);
        });
    }

    @Override
    public <T> Flux<T> execute(TransactionCallback<T> action) throws TransactionException {
        return transactional(Flux.defer(() -> Flux.from(action.doInTransaction(new ReactiveTransaction() {
            @Override
            public boolean isNewTransaction() {
                return true;
            }

            @Override
            public void setRollbackOnly() {
                throw new UnsupportedOperationException("Fail the publisher to roll back");
            }

            @Override
            public boolean isRollbackOnly() {
                return false;
            }

            @Override
            public boolean isCompleted() {
                return false;
            }
        }))));
    }

    private Mono<PostgresTransaction> begin() {
        return Mono.defer(() -> {
            final PostgresTransaction tx = new PostgresTransaction("pg-tx-" + counter.incrementAndGet());
            return Mono.fromCallable(() -> {
                        tx.setStatus(transactionManager.getTransaction(new DefaultTransactionDefinition()));
                        return tx;
                    })
                    .subscribeOn(tx.scheduler())
                    .publishOn(Schedulers.boundedElastic())
                    .doOnError(error -> tx.close());
        });
    }

    private Mono<Void> commit(PostgresTransaction tx) {
        return Mono.fromRunnable(() -> transactionManager.commit(tx.status()))
                .subscribeOn(tx.scheduler())
                .publishOn(Schedulers.boundedElastic())
                .doFinally(signal -> tx.close())
                .then();
    }

    private Mono<Void> rollback(PostgresTransaction tx) {
        return Mono.fromRunnable(() -> {
                    if (!tx.status().isCompleted()) {
                        transactionManager.rollback(tx.status());
                    }
                })
                .subscribeOn(tx.scheduler())
                .publishOn(Schedulers.boundedElastic())
                .doOnError(error -> log.error("Rollback failed", error))
                .doFinally(signal -> tx.close())
                .then();
    }
}
