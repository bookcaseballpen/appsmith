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
  Modal,
  ModalBody,
  ModalContent,
  ModalHeader,
  Option,
  Select,
  Spinner,
  Text,
  toast,
} from "@appsmith/ads";
import TeamsApi, {
  type AppGrant,
  type GrantLevel,
  type Team,
} from "ee/api/TeamsApi";
import InstanceRoleApi, { type InstanceRoleUser } from "ee/api/InstanceRoleApi";
import { callApprovalApi } from "ee/utils/approvals";

const Table = styled.table`
  border-collapse: collapse;
  margin: var(--ads-v2-spaces-3) 0 var(--ads-v2-spaces-5);
  width: 100%;

  th,
  td {
    border-bottom: 1px solid var(--ads-v2-color-border);
    padding: var(--ads-v2-spaces-3);
    text-align: left;
  }
`;

const AddRow = styled.div`
  align-items: flex-end;
  display: flex;
  gap: var(--ads-v2-spaces-3);
  margin-top: var(--ads-v2-spaces-3);
`;

const GranteeSelect = styled(Select)`
  flex: 1;
  min-width: 0;
`;

const LevelSelect = styled(Select)`
  width: 8rem;
`;

export const GRANT_LEVEL_LABELS: Record<Exclude<GrantLevel, "NONE">, string> = {
  VIEW: "View",
  EDIT: "Edit",
};

// Admins and Approvers see every app through their role, so only Devs and Users take a grant.
const GRANTABLE_ROLES = ["DEV", "USER"];

const granteeKey = (grantee: AppGrant["grantee"]) =>
  `${grantee.type}:${grantee.id}`;

interface AppAccessModalProps {
  applicationId: string;
  applicationName: string;
  onClose: () => void;
}

/**
 * Fork: the Admin's access panel for one app: who holds View or Edit on it (a team or a single user), change the
 * level, remove it, or give it to someone else. See "Teams and app grants" in FORK.md.
 */
export function AppAccessModal(props: AppAccessModalProps) {
  const { applicationId } = props;
  const [grants, setGrants] = useState<AppGrant[]>([]);
  const [teams, setTeams] = useState<Team[]>([]);
  const [users, setUsers] = useState<InstanceRoleUser[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [busyKey, setBusyKey] = useState<string | null>(null);
  const [newGrantee, setNewGrantee] = useState<string | undefined>();
  const [newLevel, setNewLevel] = useState<Exclude<GrantLevel, "NONE">>("VIEW");
  const isMounted = useRef(true);

  useEffect(function trackMounted() {
    isMounted.current = true;

    return () => {
      isMounted.current = false;
    };
  }, []);

  useEffect(
    function loadAccess() {
      const load = async () => {
        try {
          const [loadedGrants, loadedTeams, loadedUsers] = await Promise.all([
            callApprovalApi<AppGrant[]>(
              async () => TeamsApi.fetchGrants(applicationId),
              "Could not load who has access",
            ),
            callApprovalApi<Team[]>(
              async () => TeamsApi.fetchTeams(),
              "Could not load the teams",
            ),
            callApprovalApi<InstanceRoleUser[]>(
              async () => InstanceRoleApi.fetchUsers(),
              "Could not load the users",
            ),
          ]);

          if (!isMounted.current) return;

          setGrants(loadedGrants);
          setTeams(loadedTeams);
          setUsers(loadedUsers);
        } catch (error) {
          toast.show((error as Error).message, { kind: "error" });
        } finally {
          if (isMounted.current) setIsLoading(false);
        }
      };

      load();
    },
    [applicationId],
  );

  const setGrant = useCallback(
    async (grantee: AppGrant["grantee"], level: GrantLevel) => {
      setBusyKey(granteeKey(grantee));

      try {
        const updated = await callApprovalApi<AppGrant[]>(
          async () => TeamsApi.setGrant(applicationId, grantee, level),
          "Could not change the access",
        );

        if (isMounted.current) setGrants(updated);

        return true;
      } catch (error) {
        toast.show((error as Error).message, { kind: "error" });

        return false;
      } finally {
        if (isMounted.current) setBusyKey(null);
      }
    },
    [applicationId],
  );

  const granted = useMemo(
    () => new Set(grants.map((grant) => granteeKey(grant.grantee))),
    [grants],
  );

  const candidates = useMemo(
    () => [
      ...teams.map((team) => ({
        key: granteeKey({ type: "TEAM", id: team.id }),
        label: `Team: ${team.name}`,
      })),
      ...users
        .filter((user) => GRANTABLE_ROLES.includes(user.role))
        .map((user) => ({
          key: granteeKey({ type: "USER", id: user.id }),
          label: user.role === "USER" ? `${user.email} (User)` : user.email,
        })),
    ],
    [teams, users],
  );

  const addGrant = useCallback(async () => {
    if (!newGrantee) return;

    const [type, id] = newGrantee.split(":") as [
      AppGrant["grantee"]["type"],
      string,
    ];

    if (await setGrant({ type, id }, newLevel)) {
      setNewGrantee(undefined);
    }
  }, [newGrantee, newLevel, setGrant]);

  return (
    <Modal onOpenChange={(isOpen) => !isOpen && props.onClose()} open>
      <ModalContent
        data-testid="t--app-access-modal"
        style={{ width: "640px" }}
      >
        <ModalHeader>Access to {props.applicationName}</ModalHeader>
        <ModalBody>
          <Text kind="body-s" renderAs="p">
            Admins and Approvers see every app. Developers and Users see only
            the apps they or their team hold access to. Edit lets a Developer
            change the app; for a User it counts as View.
          </Text>
          {isLoading ? (
            <Spinner size="lg" />
          ) : (
            <>
              <Table>
                <thead>
                  <tr>
                    <th>Who</th>
                    <th>Access</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {grants.length === 0 && (
                    <tr>
                      <td colSpan={3}>Nobody holds access to this app yet.</td>
                    </tr>
                  )}
                  {grants.map((grant) => {
                    const key = granteeKey(grant.grantee);

                    return (
                      <tr data-testid={`t--app-grant-${grant.name}`} key={key}>
                        <td>
                          {grant.grantee.type === "TEAM"
                            ? `Team: ${grant.name}`
                            : grant.name}
                        </td>
                        <td>
                          <LevelSelect
                            isDisabled={busyKey === key}
                            onSelect={async (value: string) =>
                              setGrant(grant.grantee, value as GrantLevel)
                            }
                            size="sm"
                            value={grant.level}
                          >
                            <Option value="VIEW">View</Option>
                            <Option value="EDIT">Edit</Option>
                          </LevelSelect>
                        </td>
                        <td>
                          <Button
                            data-testid="t--app-grant-remove"
                            isDisabled={busyKey === key}
                            kind="tertiary"
                            onClick={async () =>
                              setGrant(grant.grantee, "NONE")
                            }
                            size="sm"
                            startIcon="delete-bin-line"
                          >
                            Remove
                          </Button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </Table>
              <Text kind="heading-xs" renderAs="h3">
                Give access
              </Text>
              <AddRow>
                <GranteeSelect
                  data-testid="t--app-grant-grantee"
                  onSelect={(value: string) => setNewGrantee(value)}
                  optionFilterProp="children"
                  placeholder="Pick a team or a user"
                  showSearch
                  size="md"
                  value={newGrantee}
                >
                  {candidates
                    .filter((candidate) => !granted.has(candidate.key))
                    .map((candidate) => (
                      <Option key={candidate.key} value={candidate.key}>
                        {candidate.label}
                      </Option>
                    ))}
                </GranteeSelect>
                <LevelSelect
                  data-testid="t--app-grant-level"
                  onSelect={(value: string) =>
                    setNewLevel(value as Exclude<GrantLevel, "NONE">)
                  }
                  size="md"
                  value={newLevel}
                >
                  <Option value="VIEW">View</Option>
                  <Option value="EDIT">Edit</Option>
                </LevelSelect>
                <Button
                  data-testid="t--app-grant-add"
                  isDisabled={!newGrantee || busyKey !== null}
                  onClick={addGrant}
                  size="md"
                >
                  Grant
                </Button>
              </AddRow>
            </>
          )}
        </ModalBody>
      </ModalContent>
    </Modal>
  );
}

export default AppAccessModal;
