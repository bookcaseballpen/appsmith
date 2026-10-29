package com.appsmith.server.repositories;

import com.appsmith.server.domains.ApplicationApproval;
import org.springframework.stereotype.Repository;

@Repository
public interface ApplicationApprovalRepository
        extends BaseRepository<ApplicationApproval, String>, CustomApplicationApprovalRepository {}
