import React, { useCallback, useEffect, useState } from "react";
import styled from "styled-components";
import { useSelector } from "react-redux";
import {
  Button,
  Input,
  Modal,
  ModalBody,
  ModalContent,
  ModalFooter,
  ModalHeader,
  Text,
  toast,
} from "@appsmith/ads";
import ApprovalApi, { type ApplicationApproval } from "ee/api/ApprovalApi";
import { getCurrentUser } from "selectors/usersSelectors";
import { getInstanceRole } from "ee/utils/instanceRoles";
import {
  callApprovalApi,
  formatApprovalTime,
  getApprovalPreviewPath,
  reviewsReleases,
} from "ee/utils/approvals";

const Section = styled.section`
  border: 1px solid var(--ads-v2-color-border);
  border-radius: var(--ads-v2-border-radius);
  margin: var(--ads-v2-spaces-5) 0;
  padding: var(--ads-v2-spaces-5);
`;

const Heading = styled.div`
  align-items: center;
  display: flex;
  gap: var(--ads-v2-spaces-3);
`;

const CountBadge = styled.span`
  background: var(--ads-v2-color-bg-brand);
  border-radius: 10px;
  color: var(--ads-v2-color-white);
  font-size: 12px;
  font-weight: 600;
  min-width: 20px;
  padding: 1px 7px;
  text-align: center;
`;

const Row = styled.div`
  align-items: center;
  border-top: 1px solid var(--ads-v2-color-border);
  display: flex;
  gap: var(--ads-v2-spaces-3);
  margin-top: var(--ads-v2-spaces-3);
  padding-top: var(--ads-v2-spaces-3);
`;

const RowText = styled.div`
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
`;

/**
 * Fork: the Approver's (and Admin's) list of releases waiting for approval, on the home page, with a count badge,
 * Preview, Approve and Reject (with a required comment).
 */
export function PendingApprovals() {
  const role = getInstanceRole(useSelector(getCurrentUser));
  const isReviewer = reviewsReleases(role);
  const [pending, setPending] = useState<ApplicationApproval[]>([]);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [rejecting, setRejecting] = useState<ApplicationApproval | null>(null);
  const [comment, setComment] = useState("");

  const load = useCallback(async () => {
    try {
      setPending(
        await callApprovalApi<ApplicationApproval[]>(
          async () => ApprovalApi.fetchPending(),
          "Could not load the releases waiting for approval",
        ),
      );
    } catch (error) {
      toast.show((error as Error).message, { kind: "error" });
    }
  }, []);

  useEffect(
    function loadForReviewers() {
      if (isReviewer) load();
    },
    [isReviewer, load],
  );

  const review = useCallback(
    async (
      approval: ApplicationApproval,
      call: () => Promise<unknown>,
      success: string,
    ) => {
      setBusyId(approval.applicationId);

      try {
        await callApprovalApi(call, "The request failed");
        toast.show(success, { kind: "success" });
      } catch (error) {
        toast.show((error as Error).message, { kind: "error" });
      } finally {
        setBusyId(null);
        load();
      }
    },
    [load],
  );

  const approve = useCallback(
    async (approval: ApplicationApproval) =>
      review(
        approval,
        async () => ApprovalApi.approve(approval.applicationId),
        `${approval.applicationName} approved and published`,
      ),
    [review],
  );

  const confirmReject = useCallback(async () => {
    if (!rejecting || !comment.trim()) return;

    const approval = rejecting;

    setRejecting(null);
    await review(
      approval,
      async () => ApprovalApi.reject(approval.applicationId, comment.trim()),
      `${approval.applicationName} rejected`,
    );
    setComment("");
  }, [comment, rejecting, review]);

  if (!isReviewer) return null;

  return (
    <Section data-testid="t--pending-approvals">
      <Heading>
        <Text kind="heading-s" renderAs="h2">
          Waiting for approval
        </Text>
        <CountBadge data-testid="t--pending-approvals-count">
          {pending.length}
        </CountBadge>
      </Heading>
      {pending.length === 0 && (
        <Text kind="body-s" renderAs="p">
          Nothing is waiting for approval.
        </Text>
      )}
      {pending.map((approval) => {
        const previewPath = getApprovalPreviewPath(approval);
        const isBusy = busyId === approval.applicationId;

        return (
          <Row
            data-testid={`t--pending-approval-${approval.applicationId}`}
            key={approval.applicationId}
          >
            <RowText>
              <Text kind="body-m">{approval.applicationName}</Text>
              <Text kind="body-s">
                Submitted by {approval.submittedBy},{" "}
                {formatApprovalTime(approval.submittedAt)}
                {approval.version ? ` · version ${approval.version}` : ""}
              </Text>
            </RowText>
            {previewPath && (
              <Button
                href={previewPath}
                kind="secondary"
                renderAs="a"
                size="sm"
                startIcon="eye-on"
                target="_blank"
              >
                Preview
              </Button>
            )}
            <Button
              data-testid="t--approve-release-btn"
              isDisabled={isBusy}
              isLoading={isBusy}
              kind="primary"
              onClick={async () => approve(approval)}
              size="sm"
            >
              Approve
            </Button>
            <Button
              data-testid="t--reject-release-btn"
              isDisabled={isBusy}
              kind="error"
              onClick={() => setRejecting(approval)}
              size="sm"
            >
              Reject
            </Button>
          </Row>
        );
      })}
      <Modal
        onOpenChange={(isOpen) => !isOpen && setRejecting(null)}
        open={!!rejecting}
      >
        <ModalContent style={{ width: "480px" }}>
          <ModalHeader>Reject {rejecting?.applicationName ?? ""}</ModalHeader>
          <ModalBody>
            <Input
              data-testid="t--reject-comment-input"
              isRequired
              label="Comment for the developer"
              onChange={setComment}
              placeholder="What needs to change before this can be published?"
              renderAs="textarea"
              size="md"
              value={comment}
            />
          </ModalBody>
          <ModalFooter>
            <Button
              kind="secondary"
              onClick={() => setRejecting(null)}
              size="md"
            >
              Cancel
            </Button>
            <Button
              data-testid="t--confirm-reject-btn"
              isDisabled={!comment.trim()}
              kind="error"
              onClick={confirmReject}
              size="md"
            >
              Reject
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </Section>
  );
}

export default PendingApprovals;
