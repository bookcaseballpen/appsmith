import React, { useCallback } from "react";
import { useSelector } from "react-redux";
import { Button, Tooltip, toast } from "@appsmith/ads";
import { getCurrentApplicationId } from "selectors/editorSelectors";
import { getCurrentUser } from "selectors/usersSelectors";
import { getInstanceRole } from "ee/utils/instanceRoles";
import { reviewsReleases, submitsForApproval } from "ee/utils/approvals";
import ApprovalStatusChip from "ee/components/approvals/ApprovalStatusChip";
import { useApplicationApproval } from "./useApplicationApproval";

export const SUBMIT_FOR_APPROVAL_TOOLTIP =
  "Send this version to an Approver. Any later change cancels the request.";

export const APPROVE_RELEASE_TOOLTIP =
  "Publish the submitted version to users. It is refused if the app changed since.";

/**
 * Fork: the release controls of the editor header, in place of Deploy: nobody deploys directly. Every role that opens
 * the editor sees the release status chip and submits the app for approval; Approvers and Admins also approve a
 * waiting release here (their own too), which publishes it.
 */
function ReleaseControls() {
  const applicationId = useSelector(getCurrentApplicationId);
  const role = getInstanceRole(useSelector(getCurrentUser));
  const { approval, approve, isApproving, isSubmitting, submit } =
    useApplicationApproval(applicationId);

  const handleSubmit = useCallback(async () => {
    try {
      await submit();
      toast.show("Submitted for approval", { kind: "success" });
    } catch (error) {
      toast.show((error as Error).message, { kind: "error" });
    }
  }, [submit]);

  const handleApprove = useCallback(async () => {
    try {
      await approve();
      toast.show("Approved and published", { kind: "success" });
    } catch (error) {
      toast.show((error as Error).message, { kind: "error" });
    }
  }, [approve]);

  const isOpen =
    approval?.status === "SUBMITTED" || approval?.status === "APPROVING";

  return (
    <>
      {approval && <ApprovalStatusChip approval={approval} />}
      {submitsForApproval(role) && (
        <Tooltip content={SUBMIT_FOR_APPROVAL_TOOLTIP} placement="bottomRight">
          <span>
            <Button
              data-testid="t--submit-for-approval-btn"
              isDisabled={isOpen || !approval}
              isLoading={isSubmitting}
              kind="tertiary"
              onClick={handleSubmit}
              size="md"
              startIcon="send-button"
            >
              Submit for approval
            </Button>
          </span>
        </Tooltip>
      )}
      {reviewsReleases(role) && approval?.status === "SUBMITTED" && (
        <Tooltip content={APPROVE_RELEASE_TOOLTIP} placement="bottomRight">
          <span>
            <Button
              data-testid="t--approve-release-btn"
              isLoading={isApproving}
              kind="primary"
              onClick={handleApprove}
              size="md"
            >
              Approve
            </Button>
          </span>
        </Tooltip>
      )}
    </>
  );
}

export default ReleaseControls;
