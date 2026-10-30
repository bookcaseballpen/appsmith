package com.appsmith.server.constants;

import com.appsmith.server.configurations.EmailConfig;
import com.appsmith.server.constants.ce.EmailConstantsCE;
import com.appsmith.server.domains.Organization;
import com.appsmith.server.domains.OrganizationConfiguration;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.helpers.ce.EmailServiceHelperCEImpl;
import com.appsmith.server.services.OrganizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.appsmith.server.constants.ce.EmailConstantsCE.INSTANCE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Fork: users see the product as CreativeAppBuilder, in emails and server messages. See FORK.md, "Branding".
 */
@ExtendWith(MockitoExtension.class)
class BrandTest {

    @Mock
    private OrganizationService organizationService;

    @Test
    void should_nameTheProductCreativeAppBuilder_when_aFreshInstanceHasNoName() {
        // Given / When / Then
        assertThat(Brand.PRODUCT_NAME).isEqualTo("CreativeAppBuilder");
        assertThat(Appsmith.DEFAULT_INSTANCE_NAME).isEqualTo(Brand.PRODUCT_NAME);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void should_putTheProductNameInEmails_when_theInstanceNameIsUnset(String instanceName) {
        // Given
        OrganizationConfiguration configuration = new OrganizationConfiguration();
        configuration.setInstanceName(instanceName);
        Organization organization = new Organization();
        organization.setOrganizationConfiguration(configuration);
        when(organizationService.getOrganizationConfiguration()).thenReturn(Mono.just(organization));
        EmailServiceHelperCEImpl helper = new EmailServiceHelperCEImpl(organizationService);

        // When
        Mono<Map<String, String>> params = helper.enrichWithBrandParams(new HashMap<>(), "http://localhost");

        // Then
        StepVerifier.create(params)
                .assertNext(map -> assertThat(map).containsEntry(INSTANCE_NAME, "CreativeAppBuilder"))
                .verifyComplete();
    }

    @Test
    void should_nameTheProductInEmailSubjectsAndLinks_when_emailsAreSent() {
        // Given
        List<String> texts = List.of(
                EmailConstantsCE.FORGOT_PASSWORD_EMAIL_SUBJECT,
                EmailConstantsCE.INSTANCE_ADMIN_INVITE_EMAIL_SUBJECT,
                EmailConstantsCE.INVITE_TO_WORKSPACE_EMAIL_SUBJECT_CE,
                EmailConstantsCE.PRIMARY_LINK_TEXT_INVITE_TO_INSTANCE_CE,
                EmailConstantsCE.PRIMARY_LINK_TEXT_WORKSPACE_REDIRECTION);

        // When / Then
        assertThat(texts)
                .allSatisfy(
                        text -> assertThat(text).contains("CreativeAppBuilder").doesNotContain("Appsmith"));
        assertThat(EmailConstantsCE.FORGOT_PASSWORD_EMAIL_SUBJECT).isEqualTo("Reset your CreativeAppBuilder password");
    }

    @Test
    void should_sendEmailsFromTheProductName_when_aFromAddressIsConfigured() {
        // Given
        EmailConfig emailConfig = new EmailConfig();

        // When
        emailConfig.setMailFrom("reports@example.com");

        // Then
        assertThat(emailConfig.getMailFrom().getAddress()).isEqualTo("reports@example.com");
        assertThat(emailConfig.getMailFrom().getPersonal()).isEqualTo("CreativeAppBuilder");
    }

    @Test
    void should_nameTheProductInLoginAndSignupErrors_when_theyAreShown() {
        // Given / When
        String loginMethod = AppsmithError.INVALID_LOGIN_METHOD.getMessage("Google");
        String signupDisabled = AppsmithError.SIGNUP_DISABLED.getMessage("someone@example.com");

        // Then
        assertThat(loginMethod).isEqualTo("Please use Google authentication to login to CreativeAppBuilder");
        assertThat(signupDisabled)
                .isEqualTo("Signup is restricted on this instance of CreativeAppBuilder. Please contact the "
                        + "administrator to get an invite for user someone@example.com.");
    }

    @Test
    void should_notSendUsersToAppsmithSupport_when_anErrorIsShown() {
        // Given
        List<AppsmithError> errors = Arrays.stream(AppsmithError.values())
                // Git's private-repo limit never applies in the fork (GitPrivateRepoHelperImpl).
                .filter(error -> error != AppsmithError.GIT_APPLICATION_LIMIT_ERROR)
                .toList();

        // When / Then
        assertThat(errors).allSatisfy(error -> assertThat(error.getMessage())
                .doesNotContain("Appsmith support")
                .doesNotContain("Appsmith customer support")
                .doesNotContain("Appsmith server"));
    }
}
