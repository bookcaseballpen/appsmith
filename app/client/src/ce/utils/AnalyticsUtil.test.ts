import AnalyticsUtil from "ee/utils/AnalyticsUtil";

describe("AnalyticsUtil (fork shim)", () => {
  const originalFetch = window.fetch;
  const fetchMock = jest.fn();

  beforeEach(() => {
    window.fetch = fetchMock;
    fetchMock.mockReset();
  });

  afterAll(() => {
    window.fetch = originalFetch;
  });

  it("does not load any analytics script or send requests", async () => {
    const scriptsBefore = document.querySelectorAll("script").length;

    await AnalyticsUtil.initialize({ username: "user@example.com" } as never);
    await AnalyticsUtil.identifyUser({
      username: "user@example.com",
    } as never);
    AnalyticsUtil.logEvent("APP_CRASH", { reason: "test" });
    AnalyticsUtil.reset();

    expect(document.querySelectorAll("script").length).toBe(scriptsBefore);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("has no anonymous id", () => {
    expect(AnalyticsUtil.getAnonymousId()).toBeUndefined();
  });
});
