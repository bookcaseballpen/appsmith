package com.appsmith.server.repositories.postgres;

import com.appsmith.server.repositories.BaseRepository;
import jakarta.persistence.Entity;
import org.springframework.data.repository.config.RepositoryBeanDefinitionRegistrarSupport;
import org.springframework.data.repository.config.RepositoryConfigurationExtension;
import org.springframework.data.repository.config.RepositoryConfigurationExtensionSupport;
import org.springframework.data.repository.core.RepositoryMetadata;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;

/**
 * Fork: registers the repositories of {@link EnableAppsmithRepositories}.
 */
public class AppsmithRepositoriesRegistrar extends RepositoryBeanDefinitionRegistrarSupport {

    @Override
    protected Class<? extends Annotation> getAnnotation() {
        return EnableAppsmithRepositories.class;
    }

    @Override
    protected RepositoryConfigurationExtension getExtension() {
        return new Extension();
    }

    static class Extension extends RepositoryConfigurationExtensionSupport {

        @Override
        public String getModuleName() {
            return "Appsmith PostgreSQL";
        }

        @Override
        protected String getModulePrefix() {
            return "appsmith";
        }

        @Override
        public String getRepositoryFactoryBeanClassName() {
            return AppsmithRepositoryFactoryBean.class.getName();
        }

        @Override
        protected Collection<Class<? extends Annotation>> getIdentifyingAnnotations() {
            return List.of(Entity.class);
        }

        @Override
        protected Collection<Class<?>> getIdentifyingTypes() {
            return List.of(BaseRepository.class);
        }

        @Override
        protected boolean useRepositoryConfiguration(RepositoryMetadata metadata) {
            // The repositories are reactive (Mono/Flux), which is the point of this module.
            return true;
        }
    }
}
