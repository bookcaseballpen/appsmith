import React, { useCallback, useEffect, useState } from "react";
import styled from "styled-components";
import { useDispatch, useSelector } from "react-redux";
import { Input, Tag, Text, toast } from "@appsmith/ads";
import { ReduxActionTypes } from "ee/constants/ReduxActionConstants";
import CatalogApi, { type AppTagsResult } from "ee/api/CatalogApi";
import { getCurrentApplication } from "ee/selectors/applicationSelectors";
import { getCurrentUser } from "selectors/usersSelectors";
import { createsAppsRole, getInstanceRole } from "ee/utils/instanceRoles";
import { isPermitted, PERMISSION_TYPE } from "ee/utils/permissionHelpers";
import { callApprovalApi } from "ee/utils/approvals";
import { MAX_TAG_LENGTH, MAX_TAGS, normalizeTag } from "ee/utils/catalog";

const Tags = styled.div`
  display: flex;
  flex-wrap: wrap;
  gap: var(--ads-v2-spaces-2);
  margin-top: var(--ads-v2-spaces-2);
`;

/** Why a new tag can't be added to the list, or undefined when it can. */
export const getTagError = (tag: string, tags: string[]) => {
  if (!tag) return undefined;

  if ([...tag].length > MAX_TAG_LENGTH) {
    return `A tag has at most ${MAX_TAG_LENGTH} characters`;
  }

  if (!tags.includes(tag) && tags.length >= MAX_TAGS) {
    return `An app has at most ${MAX_TAGS} tags`;
  }

  return undefined;
};

/**
 * Fork: an app's tags in the app settings. Tags are metadata, not app content, so a Dev with Edit on the app or an
 * Admin sets them even while the editor is read-only (PUT /api/v1/app-tags/applications/{id}); they never cancel a
 * release waiting for approval. Others see the tags only.
 */
export function AppTagsSetting() {
  const dispatch = useDispatch();
  const application = useSelector(getCurrentApplication);
  const role = getInstanceRole(useSelector(getCurrentUser));
  const canEdit =
    createsAppsRole(role) &&
    isPermitted(
      application?.userPermissions ?? [],
      PERMISSION_TYPE.MANAGE_APPLICATION,
    );
  const [tags, setTags] = useState<string[]>(application?.tags ?? []);
  const [draft, setDraft] = useState("");
  const [isSaving, setIsSaving] = useState(false);
  // A closed Tag hides itself; a failed save remounts the tags so a removed one shows again.
  const [revision, setRevision] = useState(0);

  useEffect(
    function syncTags() {
      setTags(application?.tags ?? []);
    },
    [application?.tags],
  );

  const save = useCallback(
    async (next: string[]) => {
      if (!application?.id) return;

      const previous = tags;

      setTags(next);
      setIsSaving(true);

      try {
        const result = await callApprovalApi<AppTagsResult>(
          async () => CatalogApi.setTags(application.id, next),
          "Could not save the tags",
        );

        dispatch({
          type: ReduxActionTypes.CURRENT_APPLICATION_TAGS_UPDATE,
          payload: result.tags,
        });
      } catch (error) {
        setTags(previous);
        setRevision((value) => value + 1);
        toast.show((error as Error).message, { kind: "error" });
      } finally {
        setIsSaving(false);
      }
    },
    [application?.id, dispatch, tags],
  );

  const draftTag = normalizeTag(draft.replace(/,/g, " "));
  const draftError = getTagError(draftTag, tags);

  const addDraft = useCallback(() => {
    if (!draftTag || draftError) return;

    setDraft("");

    if (!tags.includes(draftTag)) save([...tags, draftTag]);
  }, [draftError, draftTag, save, tags]);

  return (
    <div className="pt-2 pb-2" data-testid="t--general-settings-app-tags">
      <Text kind="action-m">Tags</Text>
      {canEdit && (
        <Input
          aria-label="Add a tag"
          errorMessage={draftError}
          id="t--general-settings-app-tags-input"
          isDisabled={isSaving}
          isValid={!draftError}
          onChange={setDraft}
          onKeyDown={(event: React.KeyboardEvent) => {
            if (event.key === "Enter" || event.key === ",") {
              event.preventDefault();
              addDraft();
            }
          }}
          placeholder="Add a tag and press Enter"
          size="md"
          type="text"
          value={draft}
        />
      )}
      <Tags>
        {tags.length === 0 && (
          <Text kind="body-s">{canEdit ? "No tags yet." : "No tags."}</Text>
        )}
        {tags.map((tag) => (
          <Tag
            data-testid={`t--app-tag-${tag}`}
            isClosable={canEdit && !isSaving}
            key={`${tag}-${revision}`}
            onClose={async () => save(tags.filter((kept) => kept !== tag))}
            size="md"
          >
            {tag}
          </Tag>
        ))}
      </Tags>
      <Text className="mt-1" kind="body-s">
        Tags help people find the app on the home page. Changing them does not
        change the app or its release.
      </Text>
    </div>
  );
}

export default AppTagsSetting;
