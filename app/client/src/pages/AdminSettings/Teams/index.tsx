import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import styled from "styled-components";
import {
  Button,
  Input,
  Option,
  Select,
  Spinner,
  Text,
  toast,
} from "@appsmith/ads";

import TeamsApi, { type Team } from "ee/api/TeamsApi";
import InstanceRoleApi, { type InstanceRoleUser } from "ee/api/InstanceRoleApi";
import { INSTANCE_ROLE_LABELS } from "ee/utils/instanceRoles";
import { callApprovalApi } from "ee/utils/approvals";

// The server's limit (TeamService).
export const MAX_TEAM_NAME_LENGTH = 100;

export const getTeamNameError = (name: string) =>
  name.trim().length > MAX_TEAM_NAME_LENGTH
    ? `A team name has at most ${MAX_TEAM_NAME_LENGTH} characters`
    : undefined;

const Wrapper = styled.div`
  flex-basis: calc(100% - ${(props) => props.theme.homePage.leftPane.width}px);
  padding: var(--ads-v2-spaces-7);
  overflow: auto;
`;

const Row = styled.div`
  align-items: flex-end;
  display: flex;
  gap: var(--ads-v2-spaces-3);
  max-width: 48rem;
`;

const TeamBox = styled.section`
  border: 1px solid var(--ads-v2-color-border);
  border-radius: var(--ads-v2-border-radius);
  margin-top: var(--ads-v2-spaces-5);
  max-width: 48rem;
  padding: var(--ads-v2-spaces-5);
`;

const TeamHeader = styled.div`
  align-items: center;
  display: flex;
  gap: var(--ads-v2-spaces-3);
  justify-content: space-between;
`;

const Members = styled.ul`
  margin: var(--ads-v2-spaces-3) 0;

  li {
    align-items: center;
    border-bottom: 1px solid var(--ads-v2-color-border);
    display: flex;
    justify-content: space-between;
    padding: var(--ads-v2-spaces-2) 0;
  }
`;

const MemberSelect = styled(Select)`
  flex: 1;
  min-width: 0;
`;

/** Runs a teams call and shows the server's message when it fails; answers undefined then. */
async function runTeamsCall<T>(
  call: () => Promise<unknown>,
  fallbackMessage: string,
): Promise<T | undefined> {
  try {
    return await callApprovalApi<T>(call, fallbackMessage);
  } catch (error) {
    toast.show((error as Error).message, { kind: "error" });

    return undefined;
  }
}

interface TeamCardProps {
  team: Team;
  users: InstanceRoleUser[];
  onChanged: (team: Team) => void;
  onDeleted: (teamId: string) => void;
}

function TeamCard({ onChanged, onDeleted, team, users }: TeamCardProps) {
  const [isRenaming, setIsRenaming] = useState(false);
  const [name, setName] = useState(team.name);
  const [description, setDescription] = useState(team.description ?? "");
  const [isConfirmingDelete, setIsConfirmingDelete] = useState(false);
  const [isBusy, setIsBusy] = useState(false);
  const [newMemberId, setNewMemberId] = useState<string | undefined>();

  const busy = useCallback(async <T,>(work: () => Promise<T>) => {
    setIsBusy(true);

    try {
      return await work();
    } finally {
      setIsBusy(false);
    }
  }, []);

  const saveRename = useCallback(async () => {
    const updated = await busy(async () =>
      runTeamsCall<Team>(
        async () => TeamsApi.updateTeam(team.id, name.trim(), description),
        "Could not rename the team",
      ),
    );

    if (updated) {
      onChanged(updated);
      setIsRenaming(false);
    }
  }, [busy, description, name, onChanged, team.id]);

  const deleteTeam = useCallback(async () => {
    const deleted = await busy(async () =>
      runTeamsCall<unknown>(
        async () => TeamsApi.deleteTeam(team.id),
        "Could not delete the team",
      ),
    );

    if (deleted !== undefined) {
      toast.show(`Team ${team.name} deleted`, { kind: "success" });
      onDeleted(team.id);
    }
  }, [busy, onDeleted, team.id, team.name]);

  const addMember = useCallback(async () => {
    if (!newMemberId) return;

    const updated = await busy(async () =>
      runTeamsCall<Team>(
        async () => TeamsApi.addMember(team.id, newMemberId),
        "Could not add the member",
      ),
    );

    if (updated) {
      onChanged(updated);
      setNewMemberId(undefined);
    }
  }, [busy, newMemberId, onChanged, team.id]);

  const removeMember = useCallback(
    async (userId: string) => {
      const updated = await busy(async () =>
        runTeamsCall<Team>(
          async () => TeamsApi.removeMember(team.id, userId),
          "Could not remove the member",
        ),
      );

      if (updated) onChanged(updated);
    },
    [busy, onChanged, team.id],
  );

  const memberIds = useMemo(
    () => new Set(team.members.map((member) => member.id)),
    [team.members],
  );

  return (
    <TeamBox data-testid={`t--team-${team.name}`}>
      {isRenaming ? (
        <Row>
          <Input
            data-testid="t--team-rename-input"
            errorMessage={getTeamNameError(name)}
            isValid={!getTeamNameError(name)}
            label="Name"
            onChange={setName}
            size="md"
            value={name}
          />
          <Input
            label="Description"
            onChange={setDescription}
            size="md"
            value={description}
          />
          <Button
            data-testid="t--team-rename-save"
            isDisabled={!name.trim() || !!getTeamNameError(name) || isBusy}
            onClick={saveRename}
            size="md"
          >
            Save
          </Button>
          <Button
            kind="secondary"
            onClick={() => {
              setName(team.name);
              setDescription(team.description ?? "");
              setIsRenaming(false);
            }}
            size="md"
          >
            Cancel
          </Button>
        </Row>
      ) : (
        <TeamHeader>
          <div>
            <Text kind="heading-s" renderAs="h2">
              {team.name}
            </Text>
            {team.description && (
              <Text kind="body-s" renderAs="p">
                {team.description}
              </Text>
            )}
          </div>
          <div className="flex gap-2">
            <Button
              data-testid="t--team-rename"
              isDisabled={isBusy}
              kind="secondary"
              onClick={() => setIsRenaming(true)}
              size="sm"
              startIcon="pencil-line"
            >
              Rename
            </Button>
            {isConfirmingDelete ? (
              <>
                <Button
                  data-testid="t--team-delete-confirm"
                  isDisabled={isBusy}
                  kind="error"
                  onClick={deleteTeam}
                  size="sm"
                >
                  Delete team and its access
                </Button>
                <Button
                  kind="secondary"
                  onClick={() => setIsConfirmingDelete(false)}
                  size="sm"
                >
                  Cancel
                </Button>
              </>
            ) : (
              <Button
                data-testid="t--team-delete"
                isDisabled={isBusy}
                kind="tertiary"
                onClick={() => setIsConfirmingDelete(true)}
                size="sm"
                startIcon="delete-bin-line"
              >
                Delete
              </Button>
            )}
          </div>
        </TeamHeader>
      )}
      <Members>
        {team.members.length === 0 && (
          <li>
            <Text kind="body-s">No members yet.</Text>
          </li>
        )}
        {team.members.map((member) => (
          <li data-testid={`t--team-member-${member.email}`} key={member.id}>
            <Text kind="body-m">
              {member.name && member.name !== member.email
                ? `${member.name} (${member.email})`
                : member.email}
            </Text>
            <Button
              data-testid="t--team-member-remove"
              isDisabled={isBusy}
              kind="tertiary"
              onClick={async () => removeMember(member.id)}
              size="sm"
            >
              Remove
            </Button>
          </li>
        ))}
      </Members>
      <Row>
        <MemberSelect
          data-testid="t--team-member-select"
          onSelect={(value: string) => setNewMemberId(value)}
          optionFilterProp="children"
          placeholder="Add a member"
          showSearch
          size="md"
          value={newMemberId}
        >
          {users
            .filter((user) => !memberIds.has(user.id))
            .map((user) => (
              <Option key={user.id} value={user.id}>
                {`${user.email} (${INSTANCE_ROLE_LABELS[user.role]})`}
              </Option>
            ))}
        </MemberSelect>
        <Button
          data-testid="t--team-member-add"
          isDisabled={!newMemberId || isBusy}
          onClick={addMember}
          size="md"
        >
          Add
        </Button>
      </Row>
    </TeamBox>
  );
}

/**
 * Fork: teams for app access. An Admin creates, renames and deletes teams and adds or removes members; the access
 * itself is given per app (the app card's "Access" item on the home page).
 */
function TeamsSettings() {
  const [teams, setTeams] = useState<Team[]>([]);
  const [users, setUsers] = useState<InstanceRoleUser[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [newName, setNewName] = useState("");
  const [newDescription, setNewDescription] = useState("");
  const [isCreating, setIsCreating] = useState(false);
  const isMounted = useRef(true);

  useEffect(function trackMounted() {
    isMounted.current = true;

    return () => {
      isMounted.current = false;
    };
  }, []);

  useEffect(function loadTeamsAndUsers() {
    const load = async () => {
      const [loadedTeams, loadedUsers] = await Promise.all([
        runTeamsCall<Team[]>(
          async () => TeamsApi.fetchTeams(),
          "Could not load the teams",
        ),
        runTeamsCall<InstanceRoleUser[]>(
          async () => InstanceRoleApi.fetchUsers(),
          "Could not load the users",
        ),
      ]);

      if (!isMounted.current) return;

      setTeams(loadedTeams ?? []);
      setUsers(loadedUsers ?? []);
      setIsLoading(false);
    };

    load();
  }, []);

  const createTeam = useCallback(async () => {
    setIsCreating(true);

    const created = await runTeamsCall<Team>(
      async () =>
        TeamsApi.createTeam(newName.trim(), newDescription.trim() || undefined),
      "Could not create the team",
    );

    if (!isMounted.current) return;

    setIsCreating(false);

    if (created) {
      setTeams((previous) => [...previous, created]);
      setNewName("");
      setNewDescription("");
      toast.show(`Team ${created.name} created`, { kind: "success" });
    }
  }, [newDescription, newName]);

  const onChanged = useCallback((team: Team) => {
    setTeams((previous) =>
      previous.map((existing) => (existing.id === team.id ? team : existing)),
    );
  }, []);

  const onDeleted = useCallback((teamId: string) => {
    setTeams((previous) => previous.filter((team) => team.id !== teamId));
  }, []);

  return (
    <Wrapper>
      <Text kind="heading-l" renderAs="h1">
        Teams
      </Text>
      <Text renderAs="p">
        Developers and Users see only the apps they or one of their teams hold
        access to. Give a team access to an app from the app&apos;s
        &quot;Access&quot; menu item on the home page. Deleting a team also
        removes the access it held.
      </Text>
      <Row className="mt-4">
        <Input
          data-testid="t--team-name-input"
          errorMessage={getTeamNameError(newName)}
          isValid={!getTeamNameError(newName)}
          label="New team"
          onChange={setNewName}
          placeholder="Team name"
          size="md"
          value={newName}
        />
        <Input
          label="Description"
          onChange={setNewDescription}
          placeholder="Optional"
          size="md"
          value={newDescription}
        />
        <Button
          data-testid="t--team-create"
          isDisabled={
            !newName.trim() || !!getTeamNameError(newName) || isCreating
          }
          isLoading={isCreating}
          onClick={createTeam}
          size="md"
        >
          Create team
        </Button>
      </Row>
      {isLoading ? (
        <Spinner size="lg" />
      ) : (
        <>
          {teams.length === 0 && (
            <Text className="mt-4" kind="body-m" renderAs="p">
              No teams yet.
            </Text>
          )}
          {teams.map((team) => (
            <TeamCard
              key={team.id}
              onChanged={onChanged}
              onDeleted={onDeleted}
              team={team}
              users={users}
            />
          ))}
        </>
      )}
    </Wrapper>
  );
}

export default TeamsSettings;
