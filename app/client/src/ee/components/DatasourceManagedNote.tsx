import React from "react";
import { useSelector } from "react-redux";
import { Text } from "@appsmith/ads";
import { getCurrentUser } from "selectors/usersSelectors";
import { getInstanceRole, isAdminRole } from "ee/utils/instanceRoles";

/**
 * Fork: where datasources are changed. There is no workspace datasources page (the workspace UI is hidden); an Admin
 * adds a datasource in the editor's Data pane ("Bring your data", or "+" above the list) and changes one with Edit on
 * its page. Devs and Approvers only see a datasource's name and type and use it in queries. See FORK.md, "Roles".
 */
export const DATASOURCES_ADMIN_EMPTY_NOTE = () =>
  "As an Admin, you add datasources here with Bring your data, and change one with Edit on its page. Every app can use them.";
export const DATASOURCES_MANAGED_BY_ADMIN_NOTE = () =>
  "An Admin manages datasources. Ask an Admin to add or change one.";
export const DATASOURCE_READ_ONLY_NOTE = () =>
  "Only an Admin can change this datasource.";

/**
 * The note for the Data pane, or undefined when there is nothing to say: Admins only need it while there are no
 * datasources, everyone else always (they can't change any datasource).
 */
export const getDataPaneNote = (
  isAdmin: boolean,
  hasDatasources: boolean,
): string | undefined => {
  if (!isAdmin) return DATASOURCES_MANAGED_BY_ADMIN_NOTE();

  return hasDatasources ? undefined : DATASOURCES_ADMIN_EMPTY_NOTE();
};

export const useIsAdmin = () =>
  isAdminRole(getInstanceRole(useSelector(getCurrentUser)));

interface DataPaneNoteProps {
  hasDatasources: boolean;
}

export function DataPaneNote({ hasDatasources }: DataPaneNoteProps) {
  const note = getDataPaneNote(useIsAdmin(), hasDatasources);

  if (!note) return null;

  return (
    <Text
      className="block px-4 py-3"
      color="var(--ads-v2-color-fg-muted)"
      data-testid="t--datasource-managed-note"
      kind="body-s"
      renderAs="p"
    >
      {note}
    </Text>
  );
}

export function DatasourceReadOnlyNote() {
  return (
    <Text
      color="var(--ads-v2-color-fg-muted)"
      data-testid="t--datasource-read-only-note"
      kind="body-s"
      renderAs="p"
    >
      {DATASOURCE_READ_ONLY_NOTE()}
    </Text>
  );
}
