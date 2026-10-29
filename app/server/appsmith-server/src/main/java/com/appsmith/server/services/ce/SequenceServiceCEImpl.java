package com.appsmith.server.services.ce;

import com.appsmith.external.helpers.ObjectIds;
import com.appsmith.external.models.BaseDomain;
import com.appsmith.server.repositories.postgres.PostgresOperations;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.sql.Timestamp;
import java.time.Instant;

@RequiredArgsConstructor
public class SequenceServiceCEImpl implements SequenceServiceCE {

    private final PostgresOperations postgresOperations;

    /**
     * Fork: an atomic upsert on the unique {@code sequence.name} (Mongo used findAndModify with upsert).
     */
    private Mono<Long> getNext(String name) {
        return postgresOperations.write(em -> {
            final Timestamp now = Timestamp.from(Instant.now());
            final Object next = em.createNativeQuery("INSERT INTO sequence (id, name, next_number, created_at,"
                            + " updated_at) VALUES (?1, ?2, 1, ?3, ?3) ON CONFLICT (name) DO UPDATE SET next_number"
                            + " = COALESCE(sequence.next_number, 0) + 1, updated_at = ?3 RETURNING next_number")
                    .setParameter(1, ObjectIds.newId())
                    .setParameter(2, name)
                    .setParameter(3, now)
                    .getSingleResult();
            return ((Number) next).longValue();
        });
    }

    @Override
    public Mono<String> getNextAsSuffix(Class<? extends BaseDomain> domainClass, String suffix) {
        final String className = domainClass.getName();
        final String name = className.substring(0, 1).toLowerCase() + className.substring(1) + suffix;
        return getNext(name).map(number -> number > 1 ? " " + number : "");
    }
}
