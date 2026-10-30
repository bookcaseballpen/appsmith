package com.appsmith.server.repositories.postgres;

import com.appsmith.server.repositories.PluginRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FreshInstallSeedTest {

    @Autowired
    PluginRepository pluginRepository;

    @Test
    void should_seedFieldsNamedWithAnAcronym_when_databaseIsFresh() {
        // Given: Flyway has run V2 on the test database

        // When / Then
        StepVerifier.create(pluginRepository.findByPackageName("google-sheets-plugin"))
                .assertNext(plugin -> {
                    assertThat(plugin.getGenerateCRUDPageComponent()).isEqualTo("Google Sheets");
                    assertThat(plugin.getIsDependentOnCS()).isTrue();
                })
                .verifyComplete();
    }
}
