package com.appsmith.server.domains;

import com.appsmith.external.helpers.CustomJsonType;
import com.appsmith.external.models.BaseDomain;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Where;

import java.io.Serializable;

@Entity
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@ToString
@NoArgsConstructor
@FieldNameConstants
public class Organization extends BaseDomain implements Serializable {

    String slug;

    String displayName;

    // Fork: jakarta.persistence.Transient, not Spring Data's: Hibernate ignores the latter and maps the column.
    @Transient
    String instanceId;

    @Transient
    String adminEmailDomainHash;

    // Fork: stored as text ('FREE'), as in the Mongo documents and the seed. An ordinal mapping cannot read it.
    @Enumerated(EnumType.STRING)
    PricingPlan pricingPlan;

    @Type(CustomJsonType.class)
    @Column(columnDefinition = "jsonb")
    OrganizationConfiguration organizationConfiguration;

    // TODO add SSO and other configurations here after migrating from environment variables to database configuration

    public static class Fields extends BaseDomain.Fields {}
}
