package com.appsmith.server.repositories.postgres;

import org.springframework.transaction.TransactionStatus;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fork: one database transaction that spans several reactive repository calls. Spring binds a JPA transaction to the
 * thread that began it, so every statement of the transaction runs on one dedicated thread, which also keeps the
 * statements of concurrent operators (zip, merge) from using the connection at the same time. The transaction rides
 * in the Reactor context under {@link #CONTEXT_KEY}; {@link PostgresOperations} looks it up there.
 */
final class PostgresTransaction {

    static final Object CONTEXT_KEY = PostgresTransaction.class;

    private final ExecutorService executor;
    private final Scheduler scheduler;
    private TransactionStatus status;

    PostgresTransaction(String threadName) {
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler = Schedulers.fromExecutorService(executor, threadName);
    }

    Scheduler scheduler() {
        return scheduler;
    }

    TransactionStatus status() {
        return status;
    }

    void setStatus(TransactionStatus status) {
        this.status = status;
    }

    /** Lets the thread end once its queued work is done. */
    void close() {
        executor.shutdown();
    }
}
