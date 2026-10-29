import React, { useCallback, useEffect, useState } from "react";
import styled from "styled-components";
import { useSelector } from "react-redux";
import { Option, Select, Spinner, Text, toast } from "@appsmith/ads";

import InstanceRoleApi, { type InstanceRoleUser } from "ee/api/InstanceRoleApi";
import {
  INSTANCE_ROLES,
  INSTANCE_ROLE_LABELS,
  type InstanceRole,
} from "ee/utils/instanceRoles";
import { getCurrentUser } from "selectors/usersSelectors";

// Responses come back unwrapped by the axios interceptors: { responseMeta, data }.
interface UnwrappedApiResponse<T> {
  responseMeta: { success: boolean; error?: { message: string } };
  data: T;
}

const Wrapper = styled.div`
  flex-basis: calc(100% - ${(props) => props.theme.homePage.leftPane.width}px);
  padding: var(--ads-v2-spaces-7);
  overflow: auto;
`;

const Table = styled.table`
  border-collapse: collapse;
  margin-top: var(--ads-v2-spaces-5);
  max-width: 48rem;
  width: 100%;

  th,
  td {
    border-bottom: 1px solid var(--ads-v2-color-border);
    padding: var(--ads-v2-spaces-3);
    text-align: left;
  }
`;

const RoleSelect = styled(Select)`
  width: 12rem;
`;

/** Lists every user with their role; an administrator changes another user's role here. */
function RolesSettings() {
  const currentUser = useSelector(getCurrentUser);
  const [users, setUsers] = useState<InstanceRoleUser[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [savingUserId, setSavingUserId] = useState<string | null>(null);

  useEffect(function loadUsers() {
    const load = async () => {
      try {
        const response =
          (await InstanceRoleApi.fetchUsers()) as unknown as UnwrappedApiResponse<
            InstanceRoleUser[]
          >;

        if (response.responseMeta.success) {
          setUsers(response.data);
        }
      } catch {
        toast.show("Could not load users", { kind: "error" });
      } finally {
        setIsLoading(false);
      }
    };

    load();
  }, []);

  const changeRole = useCallback(async (userId: string, role: InstanceRole) => {
    setSavingUserId(userId);

    try {
      const response = (await InstanceRoleApi.updateRole(
        userId,
        role,
      )) as unknown as UnwrappedApiResponse<InstanceRoleUser>;

      if (response.responseMeta.success) {
        setUsers((previous) =>
          previous.map((user) =>
            user.id === userId ? { ...user, role: response.data.role } : user,
          ),
        );
        toast.show("Role updated", { kind: "success" });
      }
    } catch {
      toast.show("Could not update the role", { kind: "error" });
    } finally {
      setSavingUserId(null);
    }
  }, []);

  return (
    <Wrapper>
      <Text kind="heading-l" renderAs="h1">
        User roles
      </Text>
      <Text renderAs="p">
        Admins manage everything. Developers build apps with existing
        datasources. Approvers review and publish apps. Users run published
        apps.
      </Text>
      {isLoading ? (
        <Spinner size="lg" />
      ) : (
        <Table>
          <thead>
            <tr>
              <th>Name</th>
              <th>Email</th>
              <th>Role</th>
            </tr>
          </thead>
          <tbody>
            {users.map((user) => (
              <tr key={user.id}>
                <td>{user.name || "-"}</td>
                <td>{user.email}</td>
                <td>
                  <RoleSelect
                    data-testid={`role-select-${user.email}`}
                    isDisabled={
                      savingUserId === user.id ||
                      user.email === currentUser?.email
                    }
                    onSelect={async (value: string) =>
                      changeRole(user.id, value as InstanceRole)
                    }
                    value={user.role}
                  >
                    {INSTANCE_ROLES.map((role) => (
                      <Option key={role} value={role}>
                        {INSTANCE_ROLE_LABELS[role]}
                      </Option>
                    ))}
                  </RoleSelect>
                </td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}
    </Wrapper>
  );
}

export default RolesSettings;
