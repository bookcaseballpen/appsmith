import React, { useCallback, useEffect, useMemo, useState } from "react";
import styled from "styled-components";
import { useDispatch, useSelector } from "react-redux";
import {
  Button,
  Option,
  SearchInput,
  SegmentedControl,
  Select,
  Tag,
  Text,
} from "@appsmith/ads";
import {
  CardListContainer,
  CardListWrapper,
  PaddingWrapper,
  ResourceHeading,
} from "pages/Applications/CommonElements";
import ApplicationCard from "pages/Applications/ApplicationCard";
import type { ModifiedMenuItemProps } from "pages/Applications/ApplicationCard";
import type { UpdateApplicationPayload } from "ee/api/ApplicationApi";
import type {
  CatalogItem,
  CatalogSort,
  CatalogTagMode,
} from "ee/api/CatalogApi";
import {
  fetchCatalog,
  fetchCatalogTags,
  setCatalogQuery,
} from "ee/actions/catalogActions";
import {
  getCatalogItems,
  getCatalogQuery,
  getCatalogTags,
  getCatalogTotal,
  getIsFetchingCatalog,
} from "ee/selectors/catalogSelectors";
import { getCurrentUser } from "selectors/usersSelectors";
import { getInstanceRole, isAdminRole } from "ee/utils/instanceRoles";
import {
  CATALOG_SORT_LABELS,
  catalogItemToApplication,
} from "ee/utils/catalog";
import AppAccessModal from "./AppAccessModal";

const Toolbar = styled.div`
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: var(--ads-v2-spaces-4);
  margin: var(--ads-v2-spaces-5) 0 var(--ads-v2-spaces-3);
`;

const SearchBox = styled.div`
  flex: 1 1 280px;
  max-width: 420px;
`;

const SortSelect = styled(Select)`
  width: 11rem;
`;

const TagFilter = styled.div`
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: var(--ads-v2-spaces-2);
  margin-bottom: var(--ads-v2-spaces-3);
`;

// The segmented control fills its parent; keep it the size of its two options, on the chips' line.
const TagModeWrapper = styled.div`
  flex: 0 0 auto;
  width: 13rem;
`;

const CardWithTags = styled.div`
  display: flex;
  flex-direction: column;
  gap: var(--ads-v2-spaces-2);
`;

const CardTags = styled.div`
  display: flex;
  flex-wrap: wrap;
  gap: var(--ads-v2-spaces-1);
  max-width: 100%;
  min-height: 20px;
`;

const Pager = styled.div`
  align-items: center;
  display: flex;
  gap: var(--ads-v2-spaces-3);
  justify-content: flex-end;
  margin: var(--ads-v2-spaces-5) 0;
`;

const EmptyState = styled.div`
  padding: var(--ads-v2-spaces-9) 0;
  text-align: center;
  width: 100%;
`;

const TAG_MODE_OPTIONS = [
  { label: "Any tag", value: "any" },
  { label: "All tags", value: "all" },
];

const SORTS: CatalogSort[] = ["name", "lastDeployedAt", "updatedAt"];

export interface CatalogSectionProps {
  deleteApplication: (applicationId: string) => void;
  enableImportExport: boolean;
  isMobile?: boolean;
  title: string;
  updateApplicationDispatch: (
    id: string,
    data: UpdateApplicationPayload,
  ) => void;
  workspaceId: string;
}

/**
 * Fork: the home page as a flat catalog of the apps the user can open. Search, tag filter, sort and paging all run on
 * the server (GET /api/v1/applications/catalog), so grants decide what each user sees. Edit shows only where the
 * server says the user may edit; Admins also get Delete and an Access item on each card.
 */
export function CatalogSection(props: CatalogSectionProps) {
  const dispatch = useDispatch();
  const role = getInstanceRole(useSelector(getCurrentUser));
  const isAdmin = isAdminRole(role);
  const query = useSelector(getCatalogQuery);
  const items = useSelector(getCatalogItems);
  const total = useSelector(getCatalogTotal);
  const isFetching = useSelector(getIsFetchingCatalog);
  const allTags = useSelector(getCatalogTags);
  // The box shows what was typed at once; the saga waits for the typing to settle before it asks the server.
  const [searchText, setSearchText] = useState(query.search);
  const [accessApp, setAccessApp] = useState<CatalogItem | null>(null);

  useEffect(
    function loadCatalog() {
      dispatch(fetchCatalog());
      dispatch(fetchCatalogTags());
    },
    [dispatch],
  );

  const onSearch = useCallback(
    (value: string) => {
      setSearchText(value);
      dispatch(setCatalogQuery({ search: value }));
    },
    [dispatch],
  );

  const toggleTag = useCallback(
    (tag: string) => {
      const tags = query.tags.includes(tag)
        ? query.tags.filter((selected) => selected !== tag)
        : [...query.tags, tag];

      dispatch(setCatalogQuery({ tags }));
    },
    [dispatch, query.tags],
  );

  const pageCount = Math.max(1, Math.ceil(total / query.size));
  const firstShown = total === 0 ? 0 : query.page * query.size + 1;
  const lastShown = Math.min(total, (query.page + 1) * query.size);
  const isFiltered = query.search.trim() !== "" || query.tags.length > 0;

  const cards = useMemo(
    () =>
      items.map((item) => ({
        item,
        application: catalogItemToApplication(item, role),
      })),
    [items, role],
  );

  return (
    <CardListContainer data-testid="t--catalog" isMobile={props.isMobile}>
      <ResourceHeading>{props.title}</ResourceHeading>
      <div>
        <Toolbar>
          <SearchBox>
            <SearchInput
              data-testid="t--catalog-search"
              onChange={onSearch}
              placeholder="Search apps by name, description or tag"
              value={searchText}
            />
          </SearchBox>
          <SortSelect
            data-testid="t--catalog-sort"
            onSelect={(value: string) =>
              dispatch(setCatalogQuery({ sort: value as CatalogSort }))
            }
            value={query.sort}
          >
            {SORTS.map((sort) => (
              <Option key={sort} value={sort}>
                {CATALOG_SORT_LABELS[sort]}
              </Option>
            ))}
          </SortSelect>
        </Toolbar>
        {allTags.length > 0 && (
          <TagFilter data-testid="t--catalog-tags">
            {allTags.map((tag) => {
              const isSelected = query.tags.includes(tag);

              return (
                <Button
                  aria-pressed={isSelected}
                  data-testid={`t--catalog-tag-${tag}`}
                  key={tag}
                  kind={isSelected ? "primary" : "secondary"}
                  onClick={() => toggleTag(tag)}
                  size="sm"
                >
                  {tag}
                </Button>
              );
            })}
            {query.tags.length > 1 && (
              <TagModeWrapper>
                <SegmentedControl
                  data-testid="t--catalog-tag-mode"
                  onChange={(value: string) =>
                    dispatch(
                      setCatalogQuery({ tagMode: value as CatalogTagMode }),
                    )
                  }
                  options={TAG_MODE_OPTIONS}
                  value={query.tagMode}
                />
              </TagModeWrapper>
            )}
            {query.tags.length > 0 && (
              <Button
                kind="tertiary"
                onClick={() => dispatch(setCatalogQuery({ tags: [] }))}
                size="sm"
              >
                Clear
              </Button>
            )}
          </TagFilter>
        )}
      </div>
      <CardListWrapper isMobile={props.isMobile}>
        {cards.map(({ application, item }) => {
          const accessItems: ModifiedMenuItemProps[] = isAdmin
            ? [
                {
                  onSelect: () => setAccessApp(item),
                  children: "Access",
                  key: "access",
                  startIcon: "user-3-line",
                  "data-testid": "t--app-access",
                },
              ]
            : [];

          return (
            <PaddingWrapper isMobile={props.isMobile} key={item.id}>
              <CardWithTags>
                <ApplicationCard
                  application={application}
                  delete={isAdmin ? props.deleteApplication : undefined}
                  enableImportExport={props.enableImportExport}
                  extraMenuItems={accessItems}
                  isFetchingApplications={isFetching && items.length === 0}
                  isMobile={props.isMobile}
                  update={
                    item.canEdit ? props.updateApplicationDispatch : undefined
                  }
                  workspaceId={props.workspaceId}
                />
                <CardTags data-testid="t--catalog-card-tags">
                  {item.tags.map((tag) => (
                    <Tag isClosable={false} key={tag} size="sm">
                      {tag}
                    </Tag>
                  ))}
                </CardTags>
              </CardWithTags>
            </PaddingWrapper>
          );
        })}
      </CardListWrapper>
      {!isFetching && items.length === 0 && (
        <EmptyState data-testid="t--catalog-empty">
          <Text kind="heading-xs">
            {isFiltered
              ? "No apps match the search or the selected tags."
              : "No apps to show yet."}
          </Text>
        </EmptyState>
      )}
      {total > query.size && (
        <Pager data-testid="t--catalog-pager">
          <Text kind="body-s">
            {firstShown}–{lastShown} of {total}
          </Text>
          <Button
            isDisabled={query.page === 0 || isFetching}
            kind="secondary"
            onClick={() => dispatch(setCatalogQuery({ page: query.page - 1 }))}
            size="sm"
            startIcon="arrow-left-s-line"
          >
            Previous
          </Button>
          <Text kind="body-s">
            Page {query.page + 1} of {pageCount}
          </Text>
          <Button
            endIcon="arrow-right-s-line"
            isDisabled={query.page + 1 >= pageCount || isFetching}
            kind="secondary"
            onClick={() => dispatch(setCatalogQuery({ page: query.page + 1 }))}
            size="sm"
          >
            Next
          </Button>
        </Pager>
      )}
      {accessApp && (
        <AppAccessModal
          applicationId={accessApp.id}
          applicationName={accessApp.name}
          onClose={() => setAccessApp(null)}
        />
      )}
    </CardListContainer>
  );
}

export default CatalogSection;
