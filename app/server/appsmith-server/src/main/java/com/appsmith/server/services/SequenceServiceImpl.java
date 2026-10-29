package com.appsmith.server.services;

import com.appsmith.server.repositories.postgres.PostgresOperations;
import com.appsmith.server.services.ce.SequenceServiceCEImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SequenceServiceImpl extends SequenceServiceCEImpl implements SequenceService {

    @Autowired
    public SequenceServiceImpl(PostgresOperations postgresOperations) {
        super(postgresOperations);
    }
}
