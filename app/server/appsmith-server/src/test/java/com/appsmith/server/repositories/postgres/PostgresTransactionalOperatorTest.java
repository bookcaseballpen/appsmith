package com.appsmith.server.repositories.postgres;

import com.appsmith.server.domains.NewAction;
import com.appsmith.server.repositories.NewActionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PostgresTransactionalOperatorTest {

    @Autowired
    TransactionalOperator transactionalOperator;

    @Autowired
    NewActionRepository newActionRepository;

    private static NewAction action(String applicationId) {
        final NewAction action = new NewAction();
        action.setApplicationId(applicationId);
        return action;
    }

    private long countActions(String applicationId) {
        return newActionRepository.findByApplicationId(applicationId).count().block();
    }

    @Test
    void should_keepAllWrites_when_publisherCompletes() {
        // Given
        final String applicationId = UUID.randomUUID().toString();

        // When
        final Mono<Long> result = Mono.zip(
                        newActionRepository.save(action(applicationId)),
                        newActionRepository.save(action(applicationId)))
                .then(newActionRepository.findByApplicationId(applicationId).count())
                .as(transactionalOperator::transactional);

        // Then
        StepVerifier.create(result).expectNext(2L).verifyComplete();
        assertThat(countActions(applicationId)).isEqualTo(2);
    }

    @Test
    void should_rollBackAllWrites_when_publisherFails() {
        // Given
        final String applicationId = UUID.randomUUID().toString();

        // When
        final Mono<NewAction> result = newActionRepository
                .save(action(applicationId))
                .then(newActionRepository.save(action(applicationId)))
                .then(Mono.<NewAction>error(new IllegalStateException("boom")))
                .as(transactionalOperator::transactional);

        // Then
        StepVerifier.create(result)
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("boom"))
                .verify();
        assertThat(countActions(applicationId)).isZero();
    }

    @Test
    void should_rollBackOuterAndInnerWrites_when_nestedTransactionalFails() {
        // Given
        final String applicationId = UUID.randomUUID().toString();
        final Mono<NewAction> inner =
                newActionRepository.save(action(applicationId)).as(transactionalOperator::transactional);

        // When
        final Flux<NewAction> result = newActionRepository
                .save(action(applicationId))
                .then(inner)
                .thenMany(Flux.<NewAction>error(new IllegalStateException("boom")))
                .as(transactionalOperator::transactional);

        // Then
        StepVerifier.create(result).expectError(IllegalStateException.class).verify();
        assertThat(countActions(applicationId)).isZero();
    }
}
