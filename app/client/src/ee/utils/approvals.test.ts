import type { ApplicationApproval } from "ee/api/ApprovalApi";
import {
  callApprovalApi,
  formatApprovalTime,
  getApprovalPreviewPath,
  getCancellationReason,
  reviewsReleases,
  submitsForApproval,
} from "./approvals";

const approval = (
  overrides: Partial<ApplicationApproval> = {},
): ApplicationApproval => ({
  applicationId: "app-1",
  applicationName: "Orders",
  workspaceId: "ws-1",
  defaultBasePageId: "page-1",
  status: "DRAFT",
  history: [],
  ...overrides,
});

describe("approval roles", () => {
  it.each([
    ["DEV", true, false],
    ["ADMIN", true, true],
    ["APPROVER", true, true],
    ["USER", false, false],
  ] as const)("%s: submits %s, reviews %s", (role, submits, reviews) => {
    expect(submitsForApproval(role)).toBe(submits);
    expect(reviewsReleases(role)).toBe(reviews);
  });
});

describe("getCancellationReason", () => {
  it("returns the reason when the last request was cancelled", () => {
    const cancelled = approval({
      history: [
        { type: "SUBMITTED", by: "dev@x", at: "2026-10-29T10:00:00Z" },
        {
          type: "CANCELLED",
          at: "2026-10-29T10:05:00Z",
          comment: "The app changed after it was submitted.",
        },
      ],
    });

    expect(getCancellationReason(cancelled)).toBe(
      "The app changed after it was submitted.",
    );
  });

  it("returns nothing once the app is submitted again", () => {
    const resubmitted = approval({
      status: "SUBMITTED",
      history: [
        { type: "CANCELLED", at: "2026-10-29T10:05:00Z", comment: "changed" },
        { type: "SUBMITTED", by: "dev@x", at: "2026-10-29T10:06:00Z" },
      ],
    });

    expect(getCancellationReason(resubmitted)).toBeUndefined();
  });
});

describe("getApprovalPreviewPath", () => {
  it("opens the default page in the editor, where approvers review", () => {
    expect(getApprovalPreviewPath(approval())).toBe(
      "/app/application/page-page-1/edit",
    );
  });

  it("has no preview without a default page", () => {
    expect(
      getApprovalPreviewPath(approval({ defaultBasePageId: null })),
    ).toBeUndefined();
  });
});

describe("formatApprovalTime", () => {
  it("reads the server's epoch seconds, not milliseconds", () => {
    const seconds = Date.UTC(2026, 9, 29, 10, 0, 0) / 1000 + 0.25;

    expect(formatApprovalTime(seconds)).toBe(
      new Date(Date.UTC(2026, 9, 29, 10, 0, 0, 250)).toLocaleString(),
    );
  });

  it("accepts ISO strings and shows nothing for no value", () => {
    expect(formatApprovalTime("2026-10-29T10:00:00Z")).toBe(
      new Date("2026-10-29T10:00:00Z").toLocaleString(),
    );
    expect(formatApprovalTime(null)).toBe("");
  });
});

describe("callApprovalApi", () => {
  it("answers the data of a successful response", async () => {
    const data = await callApprovalApi(
      async () => ({ responseMeta: { success: true }, data: { ok: 1 } }),
      "fallback",
    );

    expect(data).toEqual({ ok: 1 });
  });

  it("throws the server's message for a refused request", async () => {
    await expect(
      callApprovalApi(
        async () => ({
          responseMeta: {
            success: false,
            error: { message: "Bad request: already waiting" },
          },
          data: null,
        }),
        "fallback",
      ),
    ).rejects.toThrow("Bad request: already waiting");
  });

  it("throws the fallback when the call fails without a message", async () => {
    await expect(
      callApprovalApi(async () => Promise.reject({}), "fallback"),
    ).rejects.toThrow("fallback");
  });
});
