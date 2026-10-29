import React, { useCallback } from "react";
import { useSelector } from "react-redux";
import { Button, Tooltip, toast } from "@appsmith/ads";
import { getCurrentApplicationId } from "selectors/editorSelectors";
import { getCurrentUser } from "selectors/usersSelectors";
import { getInstanceRole } from "ee/utils/instanceRoles";
import { submitsForApproval } from "ee/utils/approvals";
import ApprovalStatusChip from "ee/components/approvals/ApprovalStatusChip";
import DeployButton from "pages/AppIDE/layouts/components/Header/DeployButton";
import { useApplicationApproval } from "./useApplicationApproval";

export const SUBMIT_FOR_APPROVAL_TOOLTIP =
  "Send this version to an Approver. Any later change cancels the request.";

/**
 * Fork: the release controls of the editor header. Devs submit the app for approval where the others deploy; every
 * role that opens the editor sees the release status chip.
 */
function ReleaseControls() {
  const applicationId = useSelector(getCurrentApplicationId);
  const role = getInstanceRole(useSelector(getCurrentUser));
  const { approval, isSubmitting, submit } =
    useApplicationApproval(applicationId);

  const handleSubmit = useCallback(async () => {
    try {
      await submit();
      toast.show("Submitted for approval", { kind: "success" });
    } catch (error) {
      toast.show((error as Error).message, { kind: "error" });
    }
  }, [submit]);

  const isWaiting = approval?.status === "SUBMITTED";

  return (
    <>
      {approval && <ApprovalStatusChip approval={approval} />}
      {submitsForApproval(role) ? (
        <Tooltip content={SUBMIT_FOR_APPROVAL_TOOLTIP} placement="bottomRight">
          <span>
            <Button
              data-testid="t--submit-for-approval-btn"
              isDisabled={isWaiting || !approval}
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
      ) : (
        <DeployButton />
      )}
    </>
  );
}

export default ReleaseControls;
