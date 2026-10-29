import { useCallback, useEffect, useState } from "react";
import ApprovalApi, { type ApplicationApproval } from "ee/api/ApprovalApi";
import { callApprovalApi } from "ee/utils/approvals";

// A submitted or approved version is cancelled by the server as soon as the app changes; poll so the chip follows.
export const APPROVAL_POLL_INTERVAL_MS = 15000;

/** Fork: the current app's approval state, kept fresh while a request is open, and a submit action. */
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
      if (status !== "SUBMITTED" && status !== "APPROVED") return;

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

  return { approval, isSubmitting, refresh, submit };
}
