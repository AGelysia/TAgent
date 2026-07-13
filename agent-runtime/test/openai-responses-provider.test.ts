import { describe, expect, it, vi } from "vitest";

import { ModelGenerationError } from "../src/providers/model-provider.js";
import { OpenAiResponsesProvider } from "../src/providers/openai-responses-provider.js";

const API_KEY = "private-api-key-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ";

function jsonResponse(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("OpenAI Responses provider", () => {
  it("checks the configured model without making a billable generation", async () => {
    const fetchImplementation = vi
      .fn()
      .mockResolvedValue(jsonResponse({ id: "gpt-test", object: "model" }));
    const provider = new OpenAiResponsesProvider({ fetch: fetchImplementation });

    await expect(
      provider.check({
        provider: "openai",
        model: "gpt-test",
        apiKey: API_KEY,
        signal: new AbortController().signal,
      }),
    ).resolves.toEqual({ ok: true });

    expect(fetchImplementation).toHaveBeenCalledOnce();
    const [url, init] = fetchImplementation.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("https://api.openai.com/v1/models/gpt-test");
    expect(init.method).toBe("GET");
    expect(init.redirect).toBe("error");
    expect(init.headers).toEqual({ Authorization: `Bearer ${API_KEY}` });
  });

  it("creates a non-stored text-only response and returns bounded fallback text", async () => {
    const fetchImplementation = vi.fn().mockResolvedValue(
      jsonResponse({
        id: "resp_test",
        output: [
          {
            type: "message",
            role: "assistant",
            content: [{ type: "output_text", text: "  Place four planks.  " }],
          },
        ],
        usage: { input_tokens: 5, output_tokens: 4, total_tokens: 9 },
      }),
    );
    const provider = new OpenAiResponsesProvider({ fetch: fetchImplementation });

    await expect(
      provider.generate({
        provider: "openai",
        model: "gpt-test",
        apiKey: API_KEY,
        input: "private player prompt",
        maxOutputTokens: 1024,
        signal: new AbortController().signal,
      }),
    ).resolves.toEqual({
      fallbackText: "Place four planks.",
      usage: { inputTokens: 5, outputTokens: 4 },
    });

    const [url, init] = fetchImplementation.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("https://api.openai.com/v1/responses");
    const body = JSON.parse(String(init.body)) as Record<string, unknown>;
    expect(body).toMatchObject({
      model: "gpt-test",
      input: "private player prompt",
      max_output_tokens: 1024,
      store: false,
      tools: [],
      tool_choice: "none",
    });
    expect(init.headers).toEqual({
      Authorization: `Bearer ${API_KEY}`,
      "Content-Type": "application/json",
    });
  });

  it.each([
    [401, "MODEL_AUTHENTICATION_FAILED"],
    [403, "MODEL_AUTHENTICATION_FAILED"],
    [404, "MODEL_UNAVAILABLE"],
    [429, "MODEL_RATE_LIMITED"],
    [500, "PROVIDER_UNAVAILABLE"],
  ] as const)("maps HTTP %s without exposing the response body", async (status, code) => {
    const fetchImplementation = vi
      .fn()
      .mockResolvedValue(new Response("private upstream detail", { status }));
    const provider = new OpenAiResponsesProvider({ fetch: fetchImplementation });

    const operation = provider.generate({
      provider: "openai",
      model: "gpt-test",
      apiKey: API_KEY,
      input: "private player prompt",
      maxOutputTokens: 1024,
      signal: new AbortController().signal,
    });
    await expect(operation).rejects.toMatchObject({ code });
    await expect(operation).rejects.not.toThrow(/private upstream detail/u);
  });

  it("rejects empty, malformed, and oversized successful responses", async () => {
    const cases = [
      jsonResponse({ output: [] }),
      jsonResponse({
        output: [
          {
            type: "message",
            role: "assistant",
            content: [{ type: "output_text", text: "\ud800" }],
          },
        ],
      }),
      new Response("not-json", { status: 200 }),
      new Response("{}", {
        status: 200,
        headers: { "Content-Length": String(1024 * 1024 + 1) },
      }),
    ];

    for (const response of cases) {
      const provider = new OpenAiResponsesProvider({
        fetch: vi.fn().mockResolvedValue(response),
      });
      await expect(
        provider.generate({
          provider: "openai",
          model: "gpt-test",
          apiKey: API_KEY,
          input: "private player prompt",
          maxOutputTokens: 1024,
          signal: new AbortController().signal,
        }),
      ).rejects.toBeInstanceOf(ModelGenerationError);
    }
  });

  it("propagates cancellation without translating it into an availability failure", async () => {
    const controller = new AbortController();
    const fetchImplementation = vi.fn(
      async (_input: string | URL | Request, init?: RequestInit): Promise<Response> =>
        new Promise((_resolve, reject) => {
          init?.signal?.addEventListener("abort", () => reject(init.signal?.reason), {
            once: true,
          });
        }),
    );
    const provider = new OpenAiResponsesProvider({ fetch: fetchImplementation });
    const operation = provider.generate({
      provider: "openai",
      model: "gpt-test",
      apiKey: API_KEY,
      input: "private player prompt",
      maxOutputTokens: 1024,
      signal: controller.signal,
    });

    controller.abort(new Error("cancelled by request owner"));
    await expect(operation).rejects.toThrow("cancelled by request owner");
  });
});
