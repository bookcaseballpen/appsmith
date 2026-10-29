package com.appsmith.server.domains;

import com.appsmith.server.domains.ce.McpKeyCE;
import jakarta.persistence.Entity;
import org.hibernate.annotations.Where;

@Entity
@Where(clause = "deleted_at IS NULL")
public class McpKey extends McpKeyCE {}
