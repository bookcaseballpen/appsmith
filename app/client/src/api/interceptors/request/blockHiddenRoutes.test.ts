import axios from "axios";
import { blockHiddenRoutes } from "api/interceptors/request";

describe("blockHiddenRoutes (fork: templates are hidden)", () => {
  const sentUrls: string[] = [];

  beforeAll(() => {
    // Stands in for the network: every request that is not answered locally ends up here.
    axios.defaults.adapter = async (config) => {
      sentUrls.push(config.url || "");

      return {
        data: "from the server",
        status: 200,
        statusText: "OK",
        headers: {},
        config,
      };
    };
  });

  beforeEach(() => {
    sentUrls.length = 0;
  });

  it.each([
    "v1/app-templates",
    "v1/app-templates/filters",
    "v1/app-templates/t1/similar",
    "v1/app-templates/t1/import/ws1",
    "/api/v1/app-templates?page=1",
  ])("answers %s locally, without sending it", async (url) => {
    const identifier = axios.interceptors.request.use((config) =>
      blockHiddenRoutes(config, { templatesHidden: true }),
    );

    const response = await axios.get(url);

    expect(response.data).toBeNull();
    expect(sentUrls).toEqual([]);

    axios.interceptors.request.eject(identifier);
  });

  it.each(["v1/applications/import/ws1", "v1/app-templatesx", "v1/users/me"])(
    "sends %s",
    async (url) => {
      const identifier = axios.interceptors.request.use((config) =>
        blockHiddenRoutes(config, { templatesHidden: true }),
      );

      const response = await axios.get(url);

      expect(response.data).toBe("from the server");
      expect(sentUrls).toEqual([url]);

      axios.interceptors.request.eject(identifier);
    },
  );

  it("sends template requests when templates are not hidden", async () => {
    const identifier = axios.interceptors.request.use((config) =>
      blockHiddenRoutes(config, { templatesHidden: false }),
    );

    await axios.get("v1/app-templates");

    expect(sentUrls).toEqual(["v1/app-templates"]);

    axios.interceptors.request.eject(identifier);
  });
});
