import type { AxiosPromise } from "axios";
import Api from "api/Api";
import type { ApiResponse } from "api/ApiResponses";

export interface TeamMember {
  id: string;
  email: string;
  name?: string | null;
}

export interface Team {
  id: string;
  name: string;
  description?: string | null;
  members: TeamMember[];
}

export type GranteeType = "TEAM" | "USER";

export type GrantLevel = "VIEW" | "EDIT" | "NONE";

export interface AppGrant {
  grantee: { type: GranteeType; id: string };
  // The team name or the user's email.
  name: string;
  level: Exclude<GrantLevel, "NONE">;
}

/**
 * Fork: teams and per-app grants (Admin only; the server refuses everyone else). See "Teams and app grants" in
 * FORK.md.
 */
export class TeamsApi extends Api {
  static url = "v1/teams";

  static async fetchTeams(): Promise<AxiosPromise<ApiResponse<Team[]>>> {
    return Api.get(TeamsApi.url);
  }

  static async createTeam(
    name: string,
    description?: string,
  ): Promise<AxiosPromise<ApiResponse<Team>>> {
    return Api.post(TeamsApi.url, { name, description });
  }

  static async updateTeam(
    teamId: string,
    name: string,
    description?: string | null,
  ): Promise<AxiosPromise<ApiResponse<Team>>> {
    return Api.put(`${TeamsApi.url}/${teamId}`, { name, description });
  }

  static async deleteTeam(
    teamId: string,
  ): Promise<AxiosPromise<ApiResponse<unknown>>> {
    return Api.delete(`${TeamsApi.url}/${teamId}`);
  }

  static async addMember(
    teamId: string,
    userId: string,
  ): Promise<AxiosPromise<ApiResponse<Team>>> {
    return Api.put(`${TeamsApi.url}/${teamId}/members/${userId}`);
  }

  static async removeMember(
    teamId: string,
    userId: string,
  ): Promise<AxiosPromise<ApiResponse<Team>>> {
    return Api.delete(`${TeamsApi.url}/${teamId}/members/${userId}`);
  }

  static async fetchGrants(
    applicationId: string,
  ): Promise<AxiosPromise<ApiResponse<AppGrant[]>>> {
    return Api.get(`v1/applications/${applicationId}/grants`);
  }

  static async setGrant(
    applicationId: string,
    grantee: AppGrant["grantee"],
    level: GrantLevel,
  ): Promise<AxiosPromise<ApiResponse<AppGrant[]>>> {
    return Api.put(`v1/applications/${applicationId}/grants`, {
      grantee,
      level,
    });
  }
}

export default TeamsApi;
