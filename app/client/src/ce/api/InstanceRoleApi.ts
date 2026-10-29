import type { AxiosPromise } from "axios";
import Api from "api/Api";
import type { ApiResponse } from "api/ApiResponses";
import type { InstanceRole } from "ee/utils/instanceRoles";

export interface InstanceRoleUser {
  id: string;
  email: string;
  name?: string;
  role: InstanceRole;
}

/** Administrator-only API to list users with their role and change a user's role. */
export class InstanceRoleApi extends Api {
  static url = "v1/admin/users";

  static async fetchUsers(): Promise<
    AxiosPromise<ApiResponse<InstanceRoleUser[]>>
  > {
    return Api.get(InstanceRoleApi.url);
  }

  static async updateRole(
    userId: string,
    role: InstanceRole,
  ): Promise<AxiosPromise<ApiResponse<InstanceRoleUser>>> {
    return Api.put(`${InstanceRoleApi.url}/${userId}/role`, { role });
  }
}

export default InstanceRoleApi;
