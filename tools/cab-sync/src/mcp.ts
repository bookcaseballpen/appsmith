// A minimal MCP client over Streamable HTTP, enough to call the Appsmith MCP server's tools: initialize, then
// tools/call, then end the session. It sends only the user's own mcp_ token; the MCP server adds what the Appsmith
// server needs to trust the call, so this tool never needs (or sees) the server's internal secret.

const PROTOCOL_VERSION = "2025-06-18";
const CLIENT_INFO = { name: "cab-sync", version: "0.1.0" };
const DEFAULT_TIMEOUT_MS = 180 * 1000;

export class McpError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "McpError";
  }
}

interface JsonRpcMessage {
  jsonrpc: "2.0";
  id?: number | string;
  method?: string;
  params?: unknown;
  result?: unknown;
  error?: { code: number; message: string; data?: unknown };
}

export interface ToolCallResult {
  isError: boolean;
  text: string;
}

export interface McpClientOptions {
  // The MCP endpoint, e.g. http://localhost:8880/mcp.
  url: string;
  token: string;
  fetchFn?: typeof fetch;
  timeoutMs?: number;
}

/** The MCP endpoint for an instance URL: the URL itself if it already ends in /mcp, otherwise <url>/mcp. */
export function mcpEndpoint(instanceUrl: string): string {
  const trimmed = instanceUrl.trim().replace(/\/+$/, "");

  return trimmed.endsWith("/mcp") ? trimmed : `${trimmed}/mcp`;
}

// The JSON-RPC messages of a text/event-stream body: each event's "data:" lines joined.
export function parseEventStream(body: string): JsonRpcMessage[] {
  return body
    .replace(/\r\n/g, "\n")
    .split("\n\n")
    .map((event) =>
      event
        .split("\n")
        .filter((line) => line.startsWith("data:"))
        .map((line) => line.slice(5).replace(/^ /, ""))
        .join("\n"),
    )
    .filter((data) => data.trim().length > 0)
    .map((data) => JSON.parse(data) as JsonRpcMessage);
}

export class McpClient {
  private readonly options: McpClientOptions;
  private readonly fetchFn: typeof fetch;
  private readonly timeoutMs: number;
  private sessionId: string | undefined;
  private nextId = 1;

  constructor(options: McpClientOptions) {
    this.options = options;
    this.fetchFn = options.fetchFn ?? fetch;
    this.timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  }

  async connect(): Promise<void> {
    await this.request("initialize", {
      protocolVersion: PROTOCOL_VERSION,
      capabilities: {},
      clientInfo: CLIENT_INFO,
    });
    await this.post({ jsonrpc: "2.0", method: "notifications/initialized" });
  }

  async callTool(name: string, args: Record<string, unknown>): Promise<ToolCallResult> {
    const result = (await this.request("tools/call", { name, arguments: args })) as {
      content?: { type: string; text?: string }[];
      isError?: boolean;
    };
    const text = (result.content ?? [])
      .filter((part) => part.type === "text" && typeof part.text === "string")
      .map((part) => part.text)
      .join("\n");

    return { isError: result.isError === true, text };
  }

  /** Calls a tool that answers JSON, and fails with the tool's own message when the call or the tool fails. */
  async callJsonTool<T>(name: string, args: Record<string, unknown>): Promise<T> {
    const { isError, text } = await this.callTool(name, args);

    if (isError) throw new McpError(`${name} failed: ${text}`);

    let parsed: unknown;

    try {
      parsed = JSON.parse(text);
    } catch {
      throw new McpError(`${name} did not answer JSON: ${text.slice(0, 500)}`);
    }

    // The fork's extension tools answer a refusal as { error, code, status } (ee/extensions/tools.ts).
    if (
      parsed !== null &&
      typeof parsed === "object" &&
      typeof (parsed as { error?: unknown }).error === "string" &&
      typeof (parsed as { code?: unknown }).code === "string"
    ) {
      const refusal = parsed as { error: string; code: string; status?: number };

      throw new McpError(
        `${name} was refused (${refusal.code}${refusal.status !== undefined ? `, HTTP ${refusal.status}` : ""}): ${refusal.error}`,
      );
    }

    return parsed as T;
  }

  async close(): Promise<void> {
    if (this.sessionId === undefined) return;

    try {
      await this.fetchFn(this.options.url, {
        method: "DELETE",
        headers: this.headers(),
        signal: AbortSignal.timeout(10 * 1000),
      });
    } catch {
      // The session expires on the server anyway.
    }

    this.sessionId = undefined;
  }

  private headers(): Record<string, string> {
    return {
      Authorization: `Bearer ${this.options.token}`,
      Accept: "application/json, text/event-stream",
      "Content-Type": "application/json",
      ...(this.sessionId !== undefined
        ? { "Mcp-Session-Id": this.sessionId, "MCP-Protocol-Version": PROTOCOL_VERSION }
        : {}),
    };
  }

  private async request(method: string, params: unknown): Promise<unknown> {
    const id = this.nextId++;
    const messages = await this.post({ jsonrpc: "2.0", id, method, params });
    const answer = messages.find((message) => message.id === id);

    if (answer === undefined) throw new McpError(`No answer to ${method} from the MCP server.`);

    if (answer.error !== undefined) {
      throw new McpError(`${method} failed: ${answer.error.message} (${answer.error.code})`);
    }

    return answer.result;
  }

  private async post(message: JsonRpcMessage): Promise<JsonRpcMessage[]> {
    let response: Response;

    try {
      response = await this.fetchFn(this.options.url, {
        method: "POST",
        headers: this.headers(),
        body: JSON.stringify(message),
        signal: AbortSignal.timeout(this.timeoutMs),
      });
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error);

      throw new McpError(`Cannot reach the MCP server at ${this.options.url}: ${reason}`);
    }

    const session = response.headers.get("mcp-session-id");

    if (session !== null) this.sessionId = session;

    if (response.status === 401) {
      throw new McpError(
        "The MCP server refused the token (401). Check CAB_TOKEN: an MCP key from your Appsmith profile (MCP keys), starting with mcp_.",
      );
    }

    if (!response.ok) {
      const body = (await response.text()).slice(0, 500);

      throw new McpError(`The MCP server answered ${response.status}: ${body}`);
    }

    if (response.status === 202) return [];

    const body = await response.text();
    const type = response.headers.get("content-type") ?? "";

    if (type.includes("text/event-stream")) return parseEventStream(body);

    if (body.trim().length === 0) return [];

    const parsed = JSON.parse(body) as JsonRpcMessage | JsonRpcMessage[];

    return Array.isArray(parsed) ? parsed : [parsed];
  }
}
