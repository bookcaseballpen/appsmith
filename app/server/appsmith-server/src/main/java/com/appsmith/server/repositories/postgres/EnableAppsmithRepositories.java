package com.appsmith.server.repositories.postgres;

import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Import;
import org.springframework.data.repository.config.BootstrapMode;
import org.springframework.data.repository.query.QueryLookupStrategy.Key;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Fork: turns the reactive repository interfaces into beans backed by PostgreSQL (replaces
 * {@code @EnableReactiveMongoRepositories}). The attributes are the ones Spring Data's annotation-based configuration
 * reads.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Import(AppsmithRepositoriesRegistrar.class)
public @interface EnableAppsmithRepositories {

    String[] value() default {};

    String[] basePackages() default {};

    Class<?>[] basePackageClasses() default {};

    Filter[] includeFilters() default {};

    Filter[] excludeFilters() default {};

    String repositoryImplementationPostfix() default "Impl";

    String namedQueriesLocation() default "";

    Key queryLookupStrategy() default Key.CREATE_IF_NOT_FOUND;

    Class<?> repositoryFactoryBeanClass() default AppsmithRepositoryFactoryBean.class;

    Class<?> repositoryBaseClass() default Object.class;

    Class<? extends BeanNameGenerator> nameGenerator() default BeanNameGenerator.class;

    boolean considerNestedRepositories() default false;

    BootstrapMode bootstrapMode() default BootstrapMode.DEFAULT;
}
