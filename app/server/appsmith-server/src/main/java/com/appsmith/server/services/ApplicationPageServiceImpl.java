package com.appsmith.server.services;

import com.appsmith.server.acl.PolicyGenerator;
import com.appsmith.server.actioncollections.base.ActionCollectionService;
import com.appsmith.server.applications.base.ApplicationService;
import com.appsmith.server.clonepage.ClonePageService;
import com.appsmith.server.domains.ActionCollection;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.NewAction;
import com.appsmith.server.domains.User;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.grants.AppGrantService;
import com.appsmith.server.helpers.CommonGitFileUtils;
import com.appsmith.server.helpers.DSLMigrationUtils;
import com.appsmith.server.layouts.UpdateLayoutService;
import com.appsmith.server.newactions.base.NewActionService;
import com.appsmith.server.newpages.base.NewPageService;
import com.appsmith.server.postpublishhooks.base.PostPublishHookCoordinatorService;
import com.appsmith.server.repositories.ActionCollectionRepository;
import com.appsmith.server.repositories.ApplicationRepository;
import com.appsmith.server.repositories.CacheableRepositoryHelper;
import com.appsmith.server.repositories.DatasourceRepository;
import com.appsmith.server.repositories.NewActionRepository;
import com.appsmith.server.repositories.NewPageRepository;
import com.appsmith.server.repositories.WorkspaceRepository;
import com.appsmith.server.roles.PublishApproval;
import com.appsmith.server.services.ce.ApplicationPageServiceCEImpl;
import com.appsmith.server.solutions.ActionPermission;
import com.appsmith.server.solutions.ApplicationPermission;
import com.appsmith.server.solutions.DatasourcePermission;
import com.appsmith.server.solutions.PagePermission;
import com.appsmith.server.solutions.WorkspacePermission;
import com.appsmith.server.staticurl.StaticUrlService;
import com.appsmith.server.themes.base.ThemeService;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

@Service
@Slf4j
public class ApplicationPageServiceImpl extends ApplicationPageServiceCEImpl implements ApplicationPageService {

    private final ApplicationRepository applicationRepository;
    private final ApplicationPermission applicationPermission;
    private final PublishApproval publishApproval;
    private final AppGrantService appGrantService;

    public ApplicationPageServiceImpl(
            WorkspaceService workspaceService,
            ApplicationService applicationService,
            StaticUrlService staticUrlService,
            SessionUserService sessionUserService,
            WorkspaceRepository workspaceRepository,
            UpdateLayoutService updateLayoutService,
            AnalyticsService analyticsService,
            PolicyGenerator policyGenerator,
            ApplicationRepository applicationRepository,
            NewPageService newPageService,
            NewActionService newActionService,
            ActionCollectionService actionCollectionService,
            CommonGitFileUtils commonGitFileUtils,
            ThemeService themeService,
            WorkspacePermission workspacePermission,
            ApplicationPermission applicationPermission,
            PagePermission pagePermission,
            ActionPermission actionPermission,
            TransactionalOperator transactionalOperator,
            PermissionGroupService permissionGroupService,
            ActionCollectionRepository actionCollectionRepository,
            NewActionRepository newActionRepository,
            NewPageRepository newPageRepository,
            DatasourceRepository datasourceRepository,
            DatasourcePermission datasourcePermission,
            DSLMigrationUtils dslMigrationUtils,
            ClonePageService<NewAction> actionClonePageService,
            ClonePageService<ActionCollection> actionCollectionClonePageService,
            ObservationRegistry observationRegistry,
            CacheableRepositoryHelper cacheableRepositoryHelper,
            PostPublishHookCoordinatorService<Application> postApplicationPublishHookCoordinatorService,
            UserDataService userDataService,
            PublishApproval publishApproval,
            AppGrantService appGrantService) {
        super(
                workspaceService,
                applicationService,
                staticUrlService,
                sessionUserService,
                workspaceRepository,
                updateLayoutService,
                analyticsService,
                policyGenerator,
                applicationRepository,
                newPageService,
                newActionService,
                actionCollectionService,
                commonGitFileUtils,
                themeService,
                workspacePermission,
                applicationPermission,
                pagePermission,
                actionPermission,
                transactionalOperator,
                permissionGroupService,
                actionCollectionRepository,
                newActionRepository,
                newPageRepository,
                datasourceRepository,
                datasourcePermission,
                dslMigrationUtils,
                actionClonePageService,
                actionCollectionClonePageService,
                observationRegistry,
                cacheableRepositoryHelper,
                postApplicationPublishHookCoordinatorService,
                userDataService);
        this.applicationRepository = applicationRepository;
        this.applicationPermission = applicationPermission;
        this.publishApproval = publishApproval;
        this.appGrantService = appGrantService;
    }

    /**
     * Every new app gets its policies here before it is saved: create, duplicate, import as a new app (also through
     * MCP) and git. A DEV can edit only the apps they hold a grant on, so the creating DEV also gets an EDIT grant on
     * the new app (see {@link AppGrantService#withCreatorGrant}); its pages, queries and JS objects inherit it.
     */
    @Override
    public Mono<Application> setApplicationPolicies(Mono<User> userMono, String workspaceId, Application application) {
        return super.setApplicationPolicies(userMono, workspaceId, application)
                .flatMap(
                        withPolicies -> userMono.flatMap(user -> appGrantService.withCreatorGrant(withPolicies, user)));
    }

    /**
     * CE deploys with edit permission alone. Here a manual deploy is refused for every role unless it is the publish of
     * an approved release ({@link PublishApproval#asApprovedRelease}), so the public {@code POST
     * /api/v1/applications/publish/{id}} always fails; that publish also needs the publish permission (APPROVER or
     * ADMIN). Internal publishes (fork) keep CE's edit check and are decided in {@link #publishWithoutPermissionChecks}.
     */
    @Override
    public Mono<Application> publish(String branchedApplicationId, boolean isPublishedManually) {
        if (!isPublishedManually) {
            return super.publish(branchedApplicationId, false);
        }
        return PublishApproval.isApprovedRelease()
                .filter(Boolean::booleanValue)
                .switchIfEmpty(Mono.error(new AppsmithException(
                        AppsmithError.ACTION_IS_NOT_AUTHORIZED,
                        "Publish application directly; submit it for approval instead")))
                .then(applicationRepository.findById(
                        branchedApplicationId, applicationPermission.getPublishPermission()))
                .switchIfEmpty(Mono.error(
                        new AppsmithException(AppsmithError.ACTION_IS_NOT_AUTHORIZED, "Publish application")))
                .then(Mono.defer(() -> super.publish(branchedApplicationId, true)));
    }

    /**
     * Every publish ends here: an approved release, git commit-and-deploy, and the server's own publishes after
     * creating, duplicating or forking an app and after a git pull, merge, discard or branch checkout. Unless it is
     * the publish of an approved release, for every signed-in role:
     * <ul>
     *   <li>on an app that is not live yet, the publish goes ahead, since users cannot see that app, but never as a
     *       manual one, so the app does not become live;</li>
     *   <li>on a live app, the publish is skipped: the change stays in edit mode until a release is approved.</li>
     * </ul>
     * Neither case fails, because git commit publishes after the commit is made (and before its lock is released). The
     * public publish endpoint is refused earlier, in {@link #publish}. Calls without a signed-in user are the server's
     * own and are not limited.
     */
    @Override
    public Mono<Application> publishWithoutPermissionChecks(String applicationId, boolean isPublishedManually) {
        return Mono.zip(
                        PublishApproval.isApprovedRelease(),
                        ReactiveSecurityContextHolder.getContext().hasElement())
                .flatMap(tuple -> {
                    if (tuple.getT1() || !tuple.getT2()) {
                        return super.publishWithoutPermissionChecks(applicationId, isPublishedManually);
                    }
                    return publishApproval.isLive(applicationId).flatMap(isLive -> {
                        if (!isLive) {
                            return super.publishWithoutPermissionChecks(applicationId, false);
                        }
                        log.debug("Not publishing live application {}: not an approved release", applicationId);
                        return applicationRepository.findById(applicationId);
                    });
                });
    }
}
