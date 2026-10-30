package com.appsmith.server.grants;

import com.appsmith.server.constants.FieldName;
import com.appsmith.server.domains.PermissionGroup;
import com.appsmith.server.domains.User;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.helpers.ce.bridge.Bridge;
import com.appsmith.server.repositories.CacheableRepositoryHelper;
import com.appsmith.server.repositories.PermissionGroupRepository;
import com.appsmith.server.repositories.UserRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Teams: named groups of users that app grants go to (see {@link AppGrantService}). A team is a permission group of
 * type {@value GrantGroups#TEAM} whose members are its {@code assignedToUserIds}; it holds no permission of its own.
 * Only an administrator creates, renames and deletes teams and changes their members. Every membership change clears
 * the member's cached permission groups, so their access changes on the next request.
 */
@Service
public class TeamService {

    private static final int MAX_NAME_LENGTH = 100;

    private final PermissionGroupRepository permissionGroupRepository;
    private final UserRepository userRepository;
    private final CacheableRepositoryHelper cacheableRepositoryHelper;
    private final AppGrantService appGrantService;

    public TeamService(
            PermissionGroupRepository permissionGroupRepository,
            UserRepository userRepository,
            CacheableRepositoryHelper cacheableRepositoryHelper,
            AppGrantService appGrantService) {
        this.permissionGroupRepository = permissionGroupRepository;
        this.userRepository = userRepository;
        this.cacheableRepositoryHelper = cacheableRepositoryHelper;
        this.appGrantService = appGrantService;
    }

    public Mono<List<TeamDTO>> list() {
        return appGrantService
                .requireAdmin()
                .thenMany(permissionGroupRepository
                        .queryBuilder()
                        .criteria(Bridge.equal(PermissionGroup.Fields.defaultDomainType, GrantGroups.TEAM))
                        .all())
                .concatMap(this::toDto)
                .sort(Comparator.comparing(team -> team.name().toLowerCase()))
                .collectList();
    }

    public Mono<TeamDTO> get(String teamId) {
        return appGrantService
                .requireAdmin()
                .then(appGrantService.findTeam(teamId))
                .flatMap(this::toDto);
    }

    public Mono<TeamDTO> create(TeamDTO.Request request) {
        String name = validName(request);
        if (name == null) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.NAME));
        }
        return appGrantService
                .requireAdmin()
                .then(requireFreeName(name, null))
                .then(cacheableRepositoryHelper.getCurrentUserOrganizationId())
                .flatMap(organizationId -> {
                    PermissionGroup team = new PermissionGroup();
                    team.setName(name);
                    team.setDescription(request.description());
                    team.setDefaultDomainType(GrantGroups.TEAM);
                    team.setOrganizationId(organizationId);
                    team.setPermissions(Set.of());
                    team.setAssignedToUserIds(new HashSet<>());
                    return permissionGroupRepository
                            .save(team)
                            .onErrorMap(DuplicateKeyException.class, error -> nameTaken(name));
                })
                .flatMap(saved -> undoIfNameTaken(
                                name,
                                saved.getId(),
                                Mono.defer(() -> permissionGroupRepository.archiveById(saved.getId())))
                        .thenReturn(saved))
                .flatMap(this::toDto);
    }

    /**
     * Renames the team and replaces its description.
     */
    public Mono<TeamDTO> update(String teamId, TeamDTO.Request request) {
        String name = validName(request);
        if (name == null) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.NAME));
        }
        return appGrantService
                .requireAdmin()
                .then(appGrantService.findTeam(teamId))
                .flatMap(team -> requireFreeName(name, teamId)
                        .then(permissionGroupRepository
                                .updateById(
                                        teamId,
                                        Bridge.update()
                                                .set(PermissionGroup.Fields.name, name)
                                                .set(PermissionGroup.Fields.description, request.description()))
                                .onErrorMap(DuplicateKeyException.class, error -> nameTaken(name)))
                        .then(undoIfNameTaken(
                                name,
                                teamId,
                                Mono.defer(() -> permissionGroupRepository.updateById(
                                        teamId,
                                        Bridge.update()
                                                .set(PermissionGroup.Fields.name, team.getName())
                                                .set(PermissionGroup.Fields.description, team.getDescription()))))))
                .then(Mono.defer(() -> appGrantService.findTeam(teamId)))
                .flatMap(this::toDto);
    }

    /**
     * Deletes the team, then revokes every grant it holds; its members lose what the team gave them. The team goes
     * first, so a failure partway never leaves a live team holding only some of its grants. A retry finds the team
     * gone and revokes the grants left over, so deleting a team that is already gone succeeds; an id of any other live
     * group is refused.
     */
    public Mono<Void> delete(String teamId) {
        return appGrantService
                .requireAdmin()
                .then(permissionGroupRepository
                        .findById(teamId)
                        .map(Optional::of)
                        .defaultIfEmpty(Optional.empty()))
                .flatMap(group -> {
                    if (group.isEmpty()) {
                        return appGrantService.revokeEverywhere(teamId);
                    }
                    PermissionGroup team = group.get();
                    if (!GrantGroups.TEAM.equals(team.getDefaultDomainType())) {
                        return Mono.error(new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, "team", teamId));
                    }
                    return permissionGroupRepository
                            .archiveById(teamId)
                            .thenMany(members(team))
                            .concatMap(appGrantService::evictPermissionGroupCache)
                            .then(appGrantService.revokeEverywhere(teamId));
                });
    }

    public Mono<TeamDTO> addMember(String teamId, String userId) {
        return changeMembership(teamId, userId, true);
    }

    public Mono<TeamDTO> removeMember(String teamId, String userId) {
        return changeMembership(teamId, userId, false);
    }

    private Mono<TeamDTO> changeMembership(String teamId, String userId, boolean add) {
        String path = PermissionGroup.Fields.assignedToUserIds;
        return appGrantService
                .requireAdmin()
                .then(appGrantService.findTeam(teamId))
                .then(userRepository
                        .findById(userId)
                        .switchIfEmpty(Mono.error(
                                new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, FieldName.USER, userId))))
                .flatMap(user -> permissionGroupRepository
                        .updateById(
                                teamId,
                                add
                                        ? Bridge.update().addToSet(path, userId)
                                        : Bridge.update().pull(path, userId))
                        .then(appGrantService.evictPermissionGroupCache(user)))
                .then(Mono.defer(() -> appGrantService.findTeam(teamId)))
                .flatMap(this::toDto);
    }

    private Mono<Void> requireFreeName(String name, String exceptTeamId) {
        return permissionGroupRepository
                .queryBuilder()
                .criteria(Bridge.<PermissionGroup>equal(PermissionGroup.Fields.defaultDomainType, GrantGroups.TEAM)
                        .equalIgnoreCase(PermissionGroup.Fields.name, name))
                .all()
                .filter(team -> !team.getId().equals(exceptTeamId))
                .hasElements()
                .flatMap(taken -> taken ? Mono.error(nameTaken(name)) : Mono.empty());
    }

    // Fork: also when a database with a unique index on team names (PostgreSQL, V7) refuses the write.
    private static AppsmithException nameTaken(String name) {
        return new AppsmithException(AppsmithError.DUPLICATE_KEY_USER_ERROR, "Team " + name, FieldName.NAME);
    }

    /**
     * Two administrators may give two teams the same name at once: each checks the name before writing and neither
     * sees the other. So the name is checked again after writing, and a writer that now sees another live team with it
     * undoes its write and gets the duplicate error. Of two such writes, the one checked last sees the other, so no
     * duplicate stays (rarely both undo).
     */
    private Mono<Void> undoIfNameTaken(String name, String teamId, Mono<?> undo) {
        return requireFreeName(name, teamId)
                .onErrorResume(AppsmithException.class, error -> undo.then(Mono.error(error)));
    }

    private Mono<TeamDTO> toDto(PermissionGroup team) {
        return members(team)
                .map(TeamDTO.Member::of)
                .sort(Comparator.comparing(member -> member.email() == null ? "" : member.email()))
                .collectList()
                .map(members -> TeamDTO.of(team, members));
    }

    private Flux<User> members(PermissionGroup team) {
        Set<String> memberIds = team.getAssignedToUserIds();
        if (memberIds == null || memberIds.isEmpty()) {
            return Flux.empty();
        }
        return userRepository
                .queryBuilder()
                .criteria(Bridge.in(User.Fields.id, memberIds))
                .all();
    }

    private static String validName(TeamDTO.Request request) {
        if (request == null || !StringUtils.hasText(request.name())) {
            return null;
        }
        String name = request.name().trim();
        return name.length() > MAX_NAME_LENGTH ? null : name;
    }
}
