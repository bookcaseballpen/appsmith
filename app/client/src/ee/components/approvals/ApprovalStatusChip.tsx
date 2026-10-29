import React from "react";
import styled from "styled-components";
import {
  Callout,
  Popover,
  PopoverBody,
  PopoverContent,
  PopoverHeader,
  PopoverTrigger,
  Text,
} from "@appsmith/ads";
import type { ApplicationApproval, ApprovalStatus } from "ee/api/ApprovalApi";
import {
  APPROVAL_STATUS_LABELS,
  formatApprovalTime,
  getCancellationReason,
} from "ee/utils/approvals";

const COLORS: Record<ApprovalStatus, { bg: string; fg: string }> = {
  DRAFT: {
    bg: "var(--ads-v2-color-bg-muted)",
    fg: "var(--ads-v2-color-fg)",
  },
  SUBMITTED: {
    bg: "var(--ads-v2-color-bg-warning)",
    fg: "var(--ads-v2-color-fg-warning)",
  },
  APPROVED: {
    bg: "var(--ads-v2-color-bg-success)",
    fg: "var(--ads-v2-color-fg-success)",
  },
  REJECTED: {
    bg: "var(--ads-v2-color-bg-error)",
    fg: "var(--ads-v2-color-fg-error)",
  },
};

const Chip = styled.button<{ status: ApprovalStatus }>`
  background: ${({ status }) => COLORS[status].bg};
  color: ${({ status }) => COLORS[status].fg};
  border: none;
  border-radius: var(--ads-v2-border-radius);
  cursor: pointer;
  font-size: 12px;
  font-weight: 500;
  margin-right: var(--ads-v2-spaces-3);
  padding: 2px 8px;
  white-space: nowrap;
`;

const Details = styled.div`
  display: flex;
  flex-direction: column;
  gap: var(--ads-v2-spaces-3);
  max-width: 320px;
`;

const History = styled.ul`
  margin: 0;
  padding-left: var(--ads-v2-spaces-5);

  li {
    font-size: 12px;
  }
`;

const EVENT_LABELS: Record<ApplicationApproval["history"][number]["type"], string> =
  {
    SUBMITTED: "Submitted",
    APPROVED: "Approved and published",
    REJECTED: "Rejected",
    CANCELLED: "Cancelled",
  };

interface Props {
  approval: ApplicationApproval;
}

/** Fork: the app's release status in the editor header; click for the reject comment and the history. */
export function ApprovalStatusChip({ approval }: Props) {
  const cancellation = getCancellationReason(approval);
  const history = [...approval.history].reverse().slice(0, 5);

  return (
    <Popover>
      <PopoverTrigger>
        <Chip
          data-testid="t--approval-status-chip"
          status={approval.status}
          type="button"
        >
          {APPROVAL_STATUS_LABELS[approval.status]}
          {approval.status === "REJECTED" ? " · see comment" : ""}
        </Chip>
      </PopoverTrigger>
      <PopoverContent align="end" size="md">
        <PopoverHeader isClosable>Release approval</PopoverHeader>
        <PopoverBody>
          <Details>
            <Text kind="body-m">
              {APPROVAL_STATUS_LABELS[approval.status]}
              {approval.version ? ` (version ${approval.version})` : ""}
            </Text>
            {approval.status === "REJECTED" && approval.comment && (
              <Callout kind="error">
                <span data-testid="t--approval-reject-comment">
                  {approval.reviewedBy ? `${approval.reviewedBy}: ` : ""}
                  {approval.comment}
                </span>
              </Callout>
            )}
            {cancellation && <Callout kind="warning">{cancellation}</Callout>}
            {approval.status === "DRAFT" && !cancellation && (
              <Text kind="body-s">
                Changes are not submitted for approval yet.
              </Text>
            )}
            {history.length > 0 && (
              <History>
                {history.map((event, index) => (
                  <li key={`${event.at}-${index}`}>
                    {EVENT_LABELS[event.type]}
                    {event.by ? ` by ${event.by}` : ""},{" "}
                    {formatApprovalTime(event.at)}
                    {event.comment && event.type === "REJECTED"
                      ? `: "${event.comment}"`
                      : ""}
                  </li>
                ))}
              </History>
            )}
          </Details>
        </PopoverBody>
      </PopoverContent>
    </Popover>
  );
}

export default ApprovalStatusChip;
