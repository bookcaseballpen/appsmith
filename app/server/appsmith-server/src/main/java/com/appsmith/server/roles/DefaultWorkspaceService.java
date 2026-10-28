package com.appsmith.server.roles;

import com.appsmith.external.models.Policy;
import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.domains.Config;
import com.appsmith.server.domains.PermissionGroup;
import com.appsmith.server.domains.User;
import com.appsmith.server.domains.Workspace;
import com.appsmith.server.domains.WorkspacePlugin;
import com.appsmith.server.dtos.Permission;
import com.appsmith.server.dtos.WorkspacePluginStatus;
import com.appsmith.server.helpers.TextUtils;
import com.appsmith.server.helpers.ce.bridge.Bridge;
import com.appsmith.server.repositories.CacheableRepositoryHelper;
import com.appsmith.server.repositories.ConfigRepository;
import com.appsmith.server.repositories.PermissionGroupRepository;
import com.appsmith.server.repositories.PluginRepository;
import com.appsmith.server.repositories.WorkspaceRepository;
import com.appsmith.server.solutions.PolicySolution;
import lombok.extern.slf4j.Slf4j;
import net.minidev.json.JSONObject;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.appsmith.server.helpers.TextUtils.generateDefaultRoleNameForResource;

/**
 * Owns the single default workspace of this fork and its four role groups ({@link InstanceRole}).
 *
 * <p>On a fresh instance the workspace and the groups are created on first start; their ids are stored in the
 * {@value #CONFIG_NAME} config document, so a restart finds and reuses them instead of creating new ones.
 */
@Slf4j
@Component
public class DefaultWorkspaceService {

    static final String CONFIG_NAME = "fork.defaultWorkspace";
    static final String WORKSPACE_ID_KEY = "workspaceId";
    static final String DEFAULT_WORKSPACE_NAME = "Apps";

    private final ConfigRepository configRepository;
    private final WorkspaceRepository workspaceRepository;
    private final PermissionGroupRepository permissionGroupRepository;
    private final PluginRepository pluginRepository;
    private final PolicySolution policySolution;
    private final CacheableRepositoryHelper cacheableRepositoryHelper;

    // The bootstrap result, shared by concurrent callers so the workspace is created at most once per process.
    private final AtomicReference<Mono<DefaultWorkspaceIds>> idsMono = new AtomicReference<>();

    public DefaultWorkspaceService(
            ConfigRepository configRepository,
            WorkspaceRepository workspaceRepository,
            PermissionGroupRepository permissionGroupRepository,
            PluginRepository pluginRepository,
            PolicySolution policySolution,
            CacheableRepositoryHelper cacheableRepositoryHelper) {
        this.configRepository = configRepository;
        this.workspaceRepository = workspaceRepository;
        this.permissionGroupRepository = permissionGroupRepository;
        this.pluginRepository = pluginRepository;
        this.policySolution = policySolution;
        this.cacheableRepositoryHelper = cacheableRepositoryHelper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapOnStartup() {
        try {
            DefaultWorkspaceIds ids = getIds().block();
            log.info("Default workspace is {}", ids == null ? null : ids.workspaceId());
        } catch (Exception e) {
            log.error("Could not create the default workspace and its role groups", e);
        }
    }

    /**
     * The default workspace and its role groups, created on the first call of a fresh instance.
     */
    public Mono<DefaultWorkspaceIds> getIds() {
        Mono<DefaultWorkspaceIds> cached = idsMono.get();
        if (cached != null) {
            return cached;
        }
        Mono<DefaultWorkspaceIds> created =
                ensureDefaultWorkspace().doOnError(error -> idsMono.set(null)).cache();
        return idsMono.compareAndSet(null, created) ? created : idsMono.get();
    }

    /**
     * Reads the stored ids, or creates the workspace and its groups when none are stored or the stored workspace is
     * gone. Idempotent: a second call returns the stored ids and creates nothing.
     */
    Mono<DefaultWorkspaceIds> ensureDefaultWorkspace() {
        return configRepository
                .findByName(CONFIG_NAME)
                .flatMap(config -> Mono.justOrEmpty(fromConfig(config)))
                .filterWhen(ids -> workspaceRepository
                        .findById(ids.workspaceId())
                        .map(workspace -> workspace.getDeletedAt() == null)
                        .defaultIfEmpty(false))
                .switchIfEmpty(Mono.defer(this::createDefaultWorkspace));
    }

    /**
     * Puts the user in the group of the given role and takes them out of the other three role groups.
     */
    public Mono<Void> assignRole(User user, InstanceRole role) {
        String userId = user.getId();
        String path = PermissionGroup.Fields.assignedToUserIds;
        return getIds().flatMap(ids -> Flux.fromArray(InstanceRole.values())
                .concatMap(groupRole -> permissionGroupRepository.updateById(
                        ids.groupId(groupRole),
                        groupRole == role
                                ? Bridge.update().addToSet(path, userId)
                                : Bridge.update().pull(path, userId)))
                .then(permissionGroupRepository.evictAllPermissionGroupCachesForUser(
                        user.getEmail(), user.getOrganizationId())));
    }

    private static DefaultWorkspaceIds fromConfig(Config config) {
        JSONObject values = config.getConfig();
        if (values == null || !StringUtils.hasText((String) values.get(WORKSPACE_ID_KEY))) {
            return null;
        }
        Map<InstanceRole, String> groupIds = new EnumMap<>(InstanceRole.class);
        for (InstanceRole role : InstanceRole.values()) {
            String groupId = (String) values.get(role.name());
            if (!StringUtils.hasText(groupId)) {
                return null;
            }
            groupIds.put(role, groupId);
        }
        return new DefaultWorkspaceIds((String) values.get(WORKSPACE_ID_KEY), groupIds);
    }

    private Mono<DefaultWorkspaceIds> createDefaultWorkspace() {
        log.info("Creating the default workspace and its role groups");
        return cacheableRepositoryHelper
                .getCurrentUserOrganizationId()
                .zipWith(pluginRepository
                        .findByDefaultInstall(true)
                        .filter(plugin -> plugin.getId() != null)
                        .map(plugin -> new WorkspacePlugin(plugin.getId(), WorkspacePluginStatus.FREE))
                        .collect(Collectors.toSet()))
                .flatMap(tuple -> {
                    Workspace workspace = new Workspace();
                    workspace.setName(DEFAULT_WORKSPACE_NAME);
                    workspace.setSlug(TextUtils.makeSlug(DEFAULT_WORKSPACE_NAME));
                    workspace.setOrganizationId(tuple.getT1());
                    workspace.setPlugins(tuple.getT2());
                    return workspaceRepository.save(workspace);
                })
                .flatMap(workspace -> createRoleGroups(workspace)
                        .flatMap(groups -> applyPolicies(workspace, groups))
                        .flatMap(groups -> saveIds(workspace, groups)));
    }

    private Mono<Map<InstanceRole, PermissionGroup>> createRoleGroups(Workspace workspace) {
        return Flux.fromArray(InstanceRole.values())
                .concatMap(role -> {
                    PermissionGroup group = new PermissionGroup();
                    group.setName(generateDefaultRoleNameForResource(role.getGroupName(), workspace.getName()));
                    group.setDescription(role.getDescription());
                    group.setDefaultDomainId(workspace.getId());
                    group.setDefaultDomainType(Workspace.class.getSimpleName());
                    group.setOrganizationId(workspace.getOrganizationId());
                    group.setPermissions(Set.of());
                    group.setAssignedToUserIds(new HashSet<>());
                    return permissionGroupRepository.save(group).map(saved -> Map.entry(role, saved));
                })
                .collectMap(Map.Entry::getKey, Map.Entry::getValue, () -> new EnumMap<>(InstanceRole.class));
    }

    private Mono<Map<InstanceRole, PermissionGroup>> applyPolicies(
            Workspace workspace, Map<InstanceRole, PermissionGroup> groups) {
        List<PermissionGroup> allGroups = List.copyOf(groups.values());
        groups.forEach((role, group) -> {
            Set<Permission> permissions = new HashSet<>();
            role.getWorkspacePermissions().stream()
                    .filter(permission -> permission.getEntity().equals(Workspace.class))
                    .forEach(permission -> permissions.add(new Permission(workspace.getId(), permission)));
            // As in a CE workspace: every member reads the role groups, the administrators assign and unassign them.
            allGroups.forEach(other -> {
                permissions.add(new Permission(other.getId(), AclPermission.READ_PERMISSION_GROUP_MEMBERS));
                if (role == InstanceRole.ADMIN) {
                    permissions.add(new Permission(other.getId(), AclPermission.ASSIGN_PERMISSION_GROUPS));
                    permissions.add(new Permission(other.getId(), AclPermission.UNASSIGN_PERMISSION_GROUPS));
                }
            });
            group.setPermissions(permissions);
        });

        Workspace withPolicies = workspace;
        for (PermissionGroup group : allGroups) {
            Map<String, Policy> workspacePolicies =
                    policySolution.generatePolicyFromPermissionGroupForObject(group, workspace.getId());
            withPolicies = policySolution.addPoliciesToExistingObject(workspacePolicies, withPolicies);
            for (PermissionGroup target : allGroups) {
                Map<String, Policy> groupPolicies =
                        policySolution.generatePolicyFromPermissionGroupForObject(group, target.getId());
                policySolution.addPoliciesToExistingObject(groupPolicies, target);
            }
        }
        withPolicies.setDefaultPermissionGroups(
                allGroups.stream().map(PermissionGroup::getId).collect(Collectors.toSet()));

        return workspaceRepository
                .save(withPolicies)
                .thenMany(Flux.fromIterable(allGroups).concatMap(permissionGroupRepository::save))
                .then(Mono.just(groups));
    }

    private Mono<DefaultWorkspaceIds> saveIds(Workspace workspace, Map<InstanceRole, PermissionGroup> groups) {
        Map<String, Object> values = new HashMap<>();
        values.put(WORKSPACE_ID_KEY, workspace.getId());
        Map<InstanceRole, String> groupIds = new EnumMap<>(InstanceRole.class);
        groups.forEach((role, group) -> {
            values.put(role.name(), group.getId());
            groupIds.put(role, group.getId());
        });
        // Replaces a stale entry (its workspace was deleted) rather than adding a second one.
        return configRepository
                .findByName(CONFIG_NAME)
                .defaultIfEmpty(new Config())
                .flatMap(config -> {
                    config.setName(CONFIG_NAME);
                    config.setConfig(new JSONObject(values));
                    return configRepository.save(config);
                })
                .thenReturn(new DefaultWorkspaceIds(workspace.getId(), groupIds));
    }
}
