import { useCallback, useEffect, useState } from "react";
import ApprovalApi, { type ApplicationApproval } from "ee/api/ApprovalApi";
import { callApprovalApi } from "ee/utils/approvals";

// A submitted or approved version is cancelled by the server as soon as the app changes; poll so the chip follows.
export const APPROVAL_POLL_INTERVAL_MS = 15000;

/** Fork: the current app's approval state, kept fresh while a request is open, and the submit and approve actions. */
export function useApplicationApproval(applicationId?: string) {
  const [approval, setApproval] = useState<ApplicationApproval | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const refresh = useCallback(async () => {
    if (!applicationId) return;

    try {
      setApproval(
        await callApprovalApi<ApplicationApproval>(
          async () => ApprovalApi.fetchStatus(applicationId),
          "Could not load the approval status",
        ),
      );
    } catch {
      // The chip just stays as it was; the next poll or action retries.
    }
  }, [applicationId]);

  useEffect(
    function loadOnAppChange() {
      setApproval(null);
      refresh();
    },
    [refresh],
  );

  const status = approval?.status;

  useEffect(
    function pollWhileOpen() {
      if (
        status !== "SUBMITTED" &&
        status !== "APPROVING" &&
        status !== "APPROVED"
      )
        return;

      const timer = setInterval(refresh, APPROVAL_POLL_INTERVAL_MS);

      return () => clearInterval(timer);
    },
    [status, refresh],
  );

  const submit = useCallback(async () => {
    if (!applicationId) return;

    setIsSubmitting(true);

    try {
      setApproval(
        await callApprovalApi<ApplicationApproval>(
          async () => ApprovalApi.submit(applicationId),
          "Could not submit the app for approval",
        ),
      );
    } finally {
      setIsSubmitting(false);
    }
  }, [applicationId]);

  const [isApproving, setIsApproving] = useState(false);

  // Approving publishes the submitted version; there is no other way to deploy.
  const approve = useCallback(async () => {
    if (!applicationId) return;

    setIsApproving(true);

    try {
      setApproval(
        await callApprovalApi<ApplicationApproval>(
          async () => ApprovalApi.approve(applicationId),
          "Could not approve the release",
        ),
      );
    } catch (error) {
      // A refused approve may have cancelled the request (the app changed); show what the server holds now.
      await refresh();
      throw error;
    } finally {
      setIsApproving(false);
    }
  }, [applicationId, refresh]);

  return { approval, approve, isApproving, isSubmitting, refresh, submit };
}
