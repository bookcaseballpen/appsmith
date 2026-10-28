# About this fork

This repository is a permanent fork of [Appsmith Community Edition](https://github.com/appsmithorg/appsmith).
It is a slimmed, AI-native, internal Appsmith: it runs on its own, with no runtime dependency on anything
Appsmith hosts (no cloud services, no telemetry, no Appsmith CDN), and Git sync stays fully usable.

- **Fork point:** upstream `release` at `728ab11367`, tagged `fork-base-728ab11367`.
- **Upstream remote:** `upstream` (`https://github.com/appsmithorg/appsmith.git`). We do not follow upstream
  releases; we only cherry-pick fixes, mainly security fixes.
- **Git host:** AWS CodeCommit will be the Git host later. Git sync has no private-repo limit and does not ask
  Appsmith's cloud for anything, so it works on any host.

## Cherry-picking upstream fixes

1. Enable conflict-resolution reuse once per clone: `git config rerere.enabled true`.
2. `git fetch upstream --tags`, then find the fix commit (security advisory, release notes).
3. On a branch: `git cherry-pick -x <sha>`. The `-x` records the upstream commit in the message.
4. On conflicts, check the list of edited upstream files below first; they are the likely spots.
   If the fix touches a file the fork deleted, skip the patch and note it in "Skipped upstream patches".
5. Run the server unit tests and the client typecheck and jest suite for the touched areas.

`git diff --stat fork-base-728ab11367..HEAD` always shows everything the fork changed.

### Conventions for fork changes

- Put fork behaviour in the non-CE `*Impl` subclasses (for example `GitPrivateRepoHelperImpl`,
  `CacheableFeatureFlagHelperImpl`) or in new files. Upstream patches land in the `*CE*` classes, so they apply.
- Where an upstream file must change, keep the edit small and mark it with a `Fork:` comment.
- One logical change per commit, message prefix `fork(...)`. No repo-wide reformatting.
- Spotless: the pre-commit hook is off in this clone (`core.hooksPath` unset). Format changed server files with
  `mvn spotless:apply -DspotlessFiles=<comma-separated regexes>`, so untouched upstream files stay as they are.

## Configuration

| Variable | Default | Effect |
|---|---|---|
| `APPSMITH_CLOUD_SERVICES_ENABLED` | `false` | Kill switch for cs.appsmith.com. When false, `CloudServicesConfig` returns empty base URLs and every consumer skips its call (see below). Set `true` only to restore upstream behaviour. |
| `APPSMITH_DISABLE_TELEMETRY` | `true` | Kept for compatibility; telemetry code is removed regardless. `docker.env.sh` now writes `true`. |

The upstream integration tests run with `appsmith.cloud_services.enabled=true`
(`app/server/appsmith-server/src/test/resources/application-test.properties`), because they expect cloud services.
The fork's disabled default is covered by unit tests of the `*Impl` classes. Test runs never reach Appsmith's cloud:
the same file sets `appsmith.cloud_services.base_url` and `signature_base_url` to `http://127.0.0.1:1`, a closed
loopback port. Unmocked calls, such as instance registration at start-up, fail locally (the log shows
"Instance registration failed ... Connection refused: /127.0.0.1:1", never "Registration successful"). Suites that need
cloud responses set their own MockWebServer URL (`ApplicationTemplateServiceTest`,
`ApplicationTemplateServicePublishTemplateTest`).

## Feature flags (local defaults)

Flag values normally come from cs.appsmith.com. With cloud services disabled they come from
`app/server/appsmith-server/src/main/java/com/appsmith/server/featureflags/ForkFeatureFlagDefaults.java`, for both
user and organization flags, and the server sends them to the client (`/api/v1/users/features`), where they are
merged over the client defaults in `app/client/src/ce/entities/FeatureFlag.ts`. Any flag not listed is `false`,
as upstream when the cloud is unreachable.

| Flag | Value | Why |
|---|---|---|
| `license_git_branch_protection_enabled` | true | Shows the protected-branch settings. The server API is not licence-gated, so "protect main" works. |
| `release_git_autocommit_feature_enabled` | true | Deprecated in CE (auto-commit is enabled per app in its Git settings); true so nothing that reads it blocks Git. |
| `release_git_autocommit_eligibility_enabled` | true | Same as above. |
| `license_git_continuous_delivery_enabled` | false | Enterprise feature, no CE server support. |
| `release_git_modularisation_enabled` | false | Keeps the legacy Git UI (`pages/Editor/gitSync`), the one upstream CE runs by default. |
| `release_git_api_contracts_enabled` | false | Keeps the Git API contracts the legacy UI uses. |
| `release_git_persist_branch_enabled` | false | Upstream default. |
| `release_git_reset_optimization_enabled` | false | Upstream default when the cloud is unreachable; the plain reset path. |
| `ab_rts_git_reset_enabled` | false | Same as above. |
| `rollout_datasource_test_rate_limit_enabled` | true | Rate-limits "test datasource" calls (brute-force protection). |
| `release_alphabetical_ordering_enabled` | true | Workspaces and apps listed alphabetically. |
| `rollout_remove_feature_walkthrough_enabled` | true | No feature walkthroughs (upstream client default). |
| `kill_session_recordings_enabled` | true | No session recordings (the recorders are removed anyway). |
| `config_user_session_recordings_enabled` | false | Same as above. |

To change a flag, edit `ForkFeatureFlagDefaults` and this table together.

## What the fork turns off, removes or stubs

### Appsmith cloud services (cs.appsmith.com)

Off by default through `APPSMITH_CLOUD_SERVICES_ENABLED`; no request is made and nothing retries.

| Caller | Behaviour when disabled | Where |
|---|---|---|
| Feature flags (user and organization) | Local defaults (above) | `CacheableFeatureFlagHelperImpl` |
| Instance registration at start-up | Skipped | `InstanceConfigHelperImpl` |
| External-IP lookup | Returns `unknown` | `NetworkUtils` |
| Release notes / "what's new" / new-version check | Empty base URL, CE code skips; the list is empty | `ReleaseNotesUtilsCEImpl` (unchanged) |
| Plugin catalogue sync (every 2 h) | Empty base URL, CE code skips; plugins stay as in the database | `PluginScheduledTaskUtilsCEImpl` (unchanged) |
| Templates (list, filters, similar) | Empty | `ApplicationTemplateServiceImpl` |
| Template details, fork, merge, building blocks, publish | Cloud-services error | `ApplicationTemplateServiceImpl` |
| Sample databases (mock data) | Empty list; creating one is a cloud-services error | `MockDataServiceImpl` |
| Upcoming integrations list | Empty | `PluginServiceImpl` |
| Git private-repo limit | No limit (see Git) | `GitPrivateRepoHelperImpl`, `GitCloudServicesUtils` |
| Pylon email hash | Never requested | `CacheablePylonHelperImpl` |
| SaaS plugin OAuth (Google Sheets and other proxied OAuth) | Authorizing fails with a cloud-services error, and so does the `/api/v1/saas/authorize` redirect. Token refresh for those plugins fails locally on the empty URL; generic OAuth2 for REST APIs is unaffected. | `AuthenticationServiceImpl`, `SaasController` |
| SaaS plugin remote execution (`/api/v1/actions/execute`) | Cloud-services error when used (the URL getter throws instead of returning a relative path) | `SharedConfigImpl` |
| Appsmith AI plugin (Appsmith-hosted AI proxy) | Cloud-services error when used. The plugin now reads the proxy URL on first use, not at load, so start-up is unaffected. Upstream already blocks creating new Appsmith AI datasources; the OpenAI, Anthropic and Google AI plugins call the provider configured locally. | `SharedConfigImpl`, `AppsmithAiPlugin` |

**Proxied OAuth is disabled.** Google Sheets (and any SaaS plugin that authorizes through Appsmith's cloud)
cannot be connected. Use a REST API datasource with your own OAuth2 app instead.

### Telemetry, support chat and usage tracking

| Item | What happened |
|---|---|
| Server Segment (events, identify, daily "Instance Active" and `instance_stats` pings) | Dependency removed. `AnalyticsServiceCEImpl` is an inert shim (many call sites), `SegmentConfig` keeps an empty CE key, `ScheduledTaskImpl` makes both pings no-ops. |
| Server Sentry | Dependency and properties removed. |
| Usage pulse | Client module deleted; server `UsagePulseServiceImpl.createPulse` records nothing. |
| Client Segment, Mixpanel (session recording), Smartlook | Wrappers and npm packages deleted. `AnalyticsUtil` is an inert shim. |
| Client Sentry | Redux enhancer, `captureException` and `ErrorBoundary` usages replaced (loglevel, our own `ErrorBoundry`); packages deleted. `SentryRoute` is only an alias of `Route` and stays. |
| BetterBugs | SDK wrapper, recording-link scripts in `index.html`, "Send support info" menu items and the npm package deleted. |
| Pylon chat (and the legacy Intercom settings) | Loader in `index.html` and the SDK script injection deleted; `bootPylon.ts` is a shim that reports the chat as unavailable, so every `window.Pylon(...)` call is unreachable. |
| Reo tracker | Deleted from `index.html`. |
| `404.html` | Smartlook and Sentry loaders deleted. |
| Docker | `Dockerfile` bakes no cloud-services URL, Segment, BetterBugs or Pylon keys. `entrypoint.sh` drops the "Instance Start" ping. `docker.env.sh` writes `APPSMITH_DISABLE_TELEMETRY=true` and `APPSMITH_CLOUD_SERVICES_ENABLED=false`. |

Left on purpose: Grafana Faro / OpenTelemetry tracing (`app/client/src/instrumentation`) only runs when an operator
sets `APPSMITH_FRONTEND_TRACING_URL`, which has no default; it then sends to that operator's own collector.

### Appsmith CDN assets

Runtime assets used to load from `assets.appsmith.com` (plugin icons stored in the database, empty states,
widget defaults, login and onboarding images). They are now committed under `app/client/public/assets-cdn/`
(57 files, about 6 MB) and served by the client build:

- `ASSETS_CDN_URL` is `/assets-cdn` (`app/client/src/constants/ThirdPartyConstants.tsx`).
- `getAssetUrl()` (`app/client/src/ce/utils/airgapHelpers.tsx`) rewrites any absolute `assets.appsmith.com` URL,
  for example plugin icons from the database, to `/assets-cdn/...`. The Image, Video and Audio widgets apply it
  when rendering, so apps saved with the upstream default URLs (including pages generated from
  `CRUD-DB-Table-Template-Application.json`) load the local copy.
- `loading.html` and the MCP builder's default image point at `/assets-cdn`.
- Email templates no longer show the Appsmith logo (it was an image on the CDN; mail clients would fetch it).
- Not vendored: the nine Git "how to" GIFs (about 70 MB). Their demo sections in the Git connect flow are hidden:
  `GIT_DEMO_GIF` holds empty strings, and all six render sites (three in `src/git/components`, three in the older
  `pages/Editor/gitSync/Tabs/GitConnectionV2`) render the section only when the GIF URL is truthy, so no
  `<img src="">` is produced. Tests cover `AddDeployKey`, `ChooseGitProvider` and `GenerateSSH`.

After a cherry-pick that adds an asset, run `node app/client/scripts/fork-vendor-assets.js --dry` from the repo
root; without `--dry` it downloads what is missing into `public/assets-cdn`. Commit the new files.

### Git without the cloud

- `GitPrivateRepoHelperImpl.isRepoLimitReached` always returns false, so connect, import and commit work for
  private repos on any host, with no limit.
- `GitCloudServicesUtils` returns "no limit" without a call when cloud services are disabled.
- Auto-commit of DSL migrations is controlled per app (`GitUtils.isAutoCommitEnabled`), not by a cloud flag.
- `GitUtils.isRepoPrivate` still sends an unauthenticated HTTPS request to the Git host's browser URL to classify
  a repo. That is the Git host (later CodeCommit), not Appsmith.

## Still referencing Appsmith hosts (left on purpose)

- **Static links** (opened by the user, nothing loads them at runtime): about 290 `docs.appsmith.com` links in
  the client and server error messages, `docs.appsmithai.com`, `community.appsmith.com`,
  `www.appsmith.com/enterprise`, the "Built on Appsmith" badge link (`appsmith.com`), Discord and GitHub release
  links. To be replaced or removed in a later task.
- **Upgrade links:** `index.html` no longer defaults `customerPortalUrl` to `customer.appsmith.com` or `pricingUrl`
  to `www.appsmith.com/pricing`; both are empty unless `APPSMITH_CUSTOMER_PORTAL_URL` / `APPSMITH_PRICING_URL` are
  set. `PRICING_PAGE_URL` and `CUSTOMER_PORTAL_URL_WITH_PARAMS` then return `""` instead of a relative
  `?source=...` link, and `useOnUpgrade` opens nothing. The upgrade buttons and ramps themselves are still shown;
  hiding them is left for a later task.
- **Placeholders and hints, never fetched:** `mock-api.appsmith.com/users` as an input placeholder, the
  `example` URLs in `packages/dsl/.../widget-configs.json`, the `release.app.appsmith.com` strings inside
  `CRUD-DB-Table-Template-Application.json`, `DEFAULT_ORIGIN_HEADER` (`app.appsmith.com`, a redirect fallback
  when a request has no Origin), `ai.appsmith.com` as the old Appsmith AI datasource URL (migration 047 and the
  plugin form) and the `HostUrlHelperCE` Javadoc examples.
- **Test fixtures and Storybook stories:** `app.appsmith.com`, `appcdn.appsmith.com`, `dev.appsmith.com` and
  `assets.appsmith.com` URLs in unit-test data and design-system stories.
- `CloudServicesConfigCE` still contains the upstream `https://cs.appsmith.com` default; it is only used when the
  kill switch is turned on.
- `ScheduledTaskCEImpl` still contains the `api.segment.io` ping code; `ScheduledTaskImpl` overrides both scheduled
  methods with no-ops, so it never runs.
- Names only, no SDK behind them: `SentryRoute` (an alias of `Route`), `logToSentry` / `getLogToSentryFromResponse`
  flags in sagas (they route errors to `appsmithTelemetry.captureException`, which only reports to the operator's
  own Faro collector, see above), the `smartlook.cloud` entry in the
  tracing ignore list, the `data-sl` canvas attribute, the `window.Sentry` / `window.Pylon` type declarations and
  comments mentioning Mixpanel or Segment.
- Every `window.Pylon(...)` call sits behind `isPylonChatAvailable()` (directly or through the menu item's
  visibility), which the shim makes false.
- `@sentry/browser` in `recommendedLibraries.ts` is a library a user can choose to install into an app; nothing
  loads it by default.
- Hint strings: the image-URL example in `utils/validation/common.ts` (`app.appsmith.com/static/...`) and the
  "request a template" link in `pages/Templates/Template/RequestTemplate.tsx` (an Appsmith-hosted form; templates
  are empty with the cloud off, so it is not reachable from the normal UI).
- Third-party, not Appsmith: Google APIs for the Google Sheets picker (`sagas/loadGoogleApi.ts`), Google Fonts on
  the static `terms-and-conditions.html`, CDNs (jsdelivr, unpkg, cdnjs) used by the custom JS library installer.

## Build- and deploy-time dependencies seen (for the follow-up task)

- `app/client/download-assets.js` (airgap builds) downloads from `assets.appsmith.com`; not used by the fork's
  build, since the assets are committed.
- `@grafana/faro-webpack-plugin` uploads source maps when `REACT_APP_FARO_*` variables are set.
- Cypress scripts in `app/client/package.json` use `CYPRESS_BASE_URL=https://dev.appsmith.com`.
- `release-cs.appsmith.com` / `release-customer.appsmith.com` in `scripts/local_testing.sh`,
  `scripts/deploy_preview.sh`, a commented example in `.env.example` and several `.github/workflows` files
  (`server-build.yml`, `server-integration-tests.yml`, `test-build-docker-image.yml`, the deploy-preview and Cypress
  workflows).
- Docker base images, registries, Helm chart repository and the GitHub Actions workflows under `.github/`.

## Skipped upstream patches

None yet.

## Upstream files this fork edits

Kept up to date with `git diff --name-status fork-base-728ab11367..HEAD`. `A` = new fork file, `D` = deleted
upstream file, `M` = edited upstream file.

### Edited upstream files (`M`)

Repo root and deploy:

- `Dockerfile`
- `deploy/docker/fs/opt/appsmith/entrypoint.sh`
- `deploy/docker/fs/opt/appsmith/templates/docker.env.sh`
- `deploy/docker/fs/opt/appsmith/templates/loading.html`
- `deploy/docker/tests/composes.sh`

Client (`app/client/`):

- `.env.example`, `build.sh`, `config/webpack.config.js`, `package.json`, `yarn.lock`
- `packages/mcp/src/builder/templates.ts`
- `public/404.html`, `public/index.html`
- `src/PluginActionEditor/components/PluginActionResponse/components/Visualization/Visualization.tsx`
- `src/api/interceptors/request/addAnonymousUserIdHeader.ts`
- `src/api/interceptors/request/apiRequestInterceptor.ts`
- `src/ce/configs/index.ts`, `src/ce/configs/index.test.ts`, `src/ce/configs/types.ts`
- `src/ce/sagas/NavigationSagas.ts`, `src/ce/sagas/userSagas.tsx`
- `src/ce/utils/Analytics/trackedUser.ts`
- `src/ce/utils/AnalyticsUtil.tsx`, `src/ce/utils/AnalyticsUtil.test.ts`
- `src/ce/utils/airgapHelpers.tsx`
- `src/constants/ThirdPartyConstants.tsx`
- `src/entities/Replay/index.ts`
- `src/git/components/ConnectModal/ConnectInitialize/ChooseGitProvider.tsx` (and `.test.tsx`)
- `src/git/components/ConnectModal/ConnectInitialize/GenerateSSH.tsx` (and `.test.tsx`)
- `src/git/components/common/AddDeployKey.tsx`, `src/git/components/common/constants.ts`
- `src/pages/AdminSettings/index.tsx`
- `src/pages/Editor/AppsmithLink.tsx`
- `src/pages/Editor/HelpButton.tsx`, `src/pages/Editor/__tests__/HelpButton.test.tsx`
- `src/pages/Editor/gitSync/Tabs/GitConnectionV2/AddDeployKey.tsx`, `ChooseGitProvider.tsx`, `GenerateSSH.tsx`,
  `constants.ts`
- `src/pages/common/SearchBar/HomepageHeaderAction.tsx` (and `__tests__/HomepageHeaderAction.test.tsx`)
- `src/store.ts`
- `src/utils/bootPylon.ts`, `src/utils/hooks/useOnUpgrade.ts`
- `src/widgets/AudioWidget/widget/index.tsx`, `src/widgets/ImageWidget/widget/index.tsx`,
  `src/widgets/VideoWidget/widget/index.tsx`
- `src/workers/Evaluation/ReplayDSL.ts`, `src/workers/Evaluation/fns/overrides/console.ts`

Server (`app/server/appsmith-server/`):

- `pom.xml`
- `src/main/java/com/appsmith/server/configurations/CloudServicesConfig.java`, `SegmentConfig.java`,
  `SharedConfigImpl.java`
- `src/main/java/com/appsmith/server/controllers/SaasController.java`
- `src/main/java/com/appsmith/server/helpers/GitCloudServicesUtils.java`, `GitPrivateRepoHelperImpl.java`,
  `InstanceConfigHelperImpl.java`, `NetworkUtils.java`
- `src/main/java/com/appsmith/server/plugins/base/PluginServiceImpl.java`
- `src/main/java/com/appsmith/server/services/AnalyticsServiceImpl.java`, `ApplicationTemplateServiceImpl.java`,
  `CacheableFeatureFlagHelperImpl.java`, `CacheablePylonHelperImpl.java`, `MockDataServiceImpl.java`,
  `UsagePulseServiceImpl.java`
- `src/main/java/com/appsmith/server/services/ce/AnalyticsServiceCEImpl.java` (the one `CE` class the fork
  rewrites: the Segment client it wrapped is gone)
- `src/main/java/com/appsmith/server/solutions/AuthenticationServiceImpl.java`, `ScheduledTaskImpl.java`
- `src/main/resources/application-ce.properties`, `application-ee.properties`
- `src/main/resources/email/ce/emailVerificationTemplate.html`, `forgotPasswordTemplate.html`,
  `instanceAdminInviteTemplate.html`, `inviteWorkspaceExistingUserTemplate.html`,
  `inviteWorkspaceNewUserTemplate.html`
- `src/test/java/com/appsmith/server/git/ops/GitConnectTests.java`
- `src/test/java/com/appsmith/server/helpers/ce/GitPrivateRepoCEImplTest.java`
- `src/test/resources/application-test.properties`

Server plugins (`app/server/appsmith-plugins/`):

- `appsmithAiPlugin/src/main/java/com/external/plugins/AppsmithAiPlugin.java`

### Deleted upstream files (`D`)

- Client: `src/ce/constants/UsagePulse.ts`, `src/ee/constants/UsagePulse.ts`, `src/ce/hooks/useBetterbugsMetadata.ts`,
  `src/ee/hooks/useBetterbugsMetadata.ts`, `src/ce/hooks/__tests__/useBetterbugsMetadata.test.tsx`,
  `src/ce/sagas/userSagas.test.ts`, `src/usagePulse/` (`index.ts`, `utils.ts` and their tests),
  `src/utils/Analytics/betterbugs.ts`, `mixpanel.ts`, `segment.ts`, `smartlook.ts`, `segment.test.ts`,
  `__tests__/betterbugs.test.ts`
- Server tests: `services/UsagePulseServiceTest.java`, `services/ce/AnalyticsServiceCEImplTest.java`

### New fork files (`A`)

- `FORK.md`
- `app/client/public/assets-cdn/` (vendored assets)
- `app/client/scripts/fork-vendor-assets.js`
- `app/client/src/ce/utils/airgapHelpers.test.ts`, `app/client/src/constants/ThirdPartyConstants.test.ts`,
  `app/client/src/git/components/common/AddDeployKey.test.tsx`
- `app/server/appsmith-server/src/main/java/com/appsmith/server/featureflags/ForkFeatureFlagDefaults.java`
- `app/server/appsmith-plugins/appsmithAiPlugin/src/test/java/com/external/plugins/AppsmithAiPluginExecutorTest.java`
- Server unit tests: `configurations/CloudServicesConfigTest`, `configurations/SharedConfigImplTest`,
  `controllers/SaasControllerTest`, `helpers/GitCloudServicesUtilsTest`,
  `helpers/GitPrivateRepoHelperImplTest`, `helpers/InstanceConfigHelperImplTest`, `helpers/NetworkUtilsTest`,
  `services/AnalyticsServiceImplTest`, `services/ApplicationTemplateServiceImplTest`,
  `services/CacheableFeatureFlagHelperImplTest`, `services/CacheablePylonHelperImplTest`,
  `services/MockDataServiceImplTest`, `services/UsagePulseServiceImplTest`
