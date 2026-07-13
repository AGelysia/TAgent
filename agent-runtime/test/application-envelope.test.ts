import { describe, expect, it } from "vitest";

import { SchemaRegistry } from "../src/protocol/schema-registry.js";
import type {
  AgentTerminalResponse,
  StructuredTextView,
} from "../src/requests/agent-request-service.js";
import {
  APPLICATION_MAXIMUM_BYTES,
  ApplicationEnvelopeProtocol,
} from "../src/transport/application-envelope.js";
import { HandshakeReplayCache } from "../src/transport/replay-cache.js";

const NOW = new Date("2026-07-13T00:00:00.000Z");
const REQUEST_ID = "11111111-1111-4111-8111-111111111111";
const MESSAGE_ID = "22222222-2222-4222-8222-222222222222";
const PLAYER_UUID = "33333333-3333-4333-8333-333333333333";
const FIRST_VIEW_ID = "44444444-4444-4444-8444-444444444444";
const SECOND_VIEW_ID = "55555555-5555-4555-8555-555555555555";
const NONCE = Buffer.alloc(16, 0x11).toString("base64url");
const schemas = await SchemaRegistry.load();

function protocol(): ApplicationEnvelopeProtocol {
  return new ApplicationEnvelopeProtocol({
    serverId: "test-server",
    schemaRegistry: schemas,
    replayCache: new HandshakeReplayCache({ ttlMilliseconds: 1_000, maximumEntries: 4 }),
    now: () => NOW,
    randomBytes: (size) => Buffer.alloc(size, 0x11),
    randomUuid: () => MESSAGE_ID,
  });
}

function view(viewId: string, text: string, fallbackText = "ok"): StructuredTextView {
  return {
    viewSchemaVersion: "1.0",
    viewId,
    requestId: REQUEST_ID,
    viewType: "text",
    revision: 1,
    title: "Agent response",
    fallbackText,
    pinnable: true,
    content: { text },
  };
}

function completion(
  fallbackText: string,
  structuredViews: readonly StructuredTextView[],
): AgentTerminalResponse {
  return {
    type: "agent.complete",
    payload: {
      sessionId: null,
      playerUuid: PLAYER_UUID,
      fallbackText,
      structuredViews,
    },
  };
}

function expectedEnvelope(response: AgentTerminalResponse): Record<string, unknown> {
  return {
    protocolVersion: "1.0",
    messageId: MESSAGE_ID,
    requestId: REQUEST_ID,
    serverId: "test-server",
    type: response.type,
    timestamp: NOW.toISOString(),
    nonce: NONCE,
    payload: response.payload,
  };
}

function encodedByteLength(value: unknown): number {
  return Buffer.byteLength(JSON.stringify(value), "utf8");
}

function payload(envelope: Record<string, unknown>): Record<string, unknown> {
  const value = envelope["payload"];
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new TypeError("Expected an object payload");
  }
  return value as Record<string, unknown>;
}

describe("Application response envelope limits", () => {
  it.each([
    ["Chinese", "汉"],
    ["three-byte BMP", "\u0800"],
  ])("preserves a maximum-length %s fallback while omitting oversized views", (_name, unit) => {
    const fallbackText = unit.repeat(8192);
    const response = completion(fallbackText, [view(FIRST_VIEW_ID, fallbackText, fallbackText)]);

    expect(encodedByteLength(expectedEnvelope(response))).toBeGreaterThan(
      APPLICATION_MAXIMUM_BYTES,
    );

    const envelope = protocol().createResponse(REQUEST_ID, response);

    expect(payload(envelope)).toMatchObject({ fallbackText, structuredViews: [] });
    expect(encodedByteLength(envelope)).toBeLessThanOrEqual(APPLICATION_MAXIMUM_BYTES);
  });

  it("retains views at the UTF-8 byte limit and drops them one byte beyond it", () => {
    const firstText = "x".repeat(32_768);
    const minimumResponse = completion("ok", [
      view(FIRST_VIEW_ID, firstText),
      view(SECOND_VIEW_ID, "x"),
    ]);
    const secondTextLength =
      APPLICATION_MAXIMUM_BYTES - encodedByteLength(expectedEnvelope(minimumResponse)) + 1;

    expect(secondTextLength).toBeGreaterThan(1);
    expect(secondTextLength).toBeLessThan(32_768);

    const boundaryResponse = completion("ok", [
      view(FIRST_VIEW_ID, firstText),
      view(SECOND_VIEW_ID, "x".repeat(secondTextLength)),
    ]);
    const boundaryEnvelope = protocol().createResponse(REQUEST_ID, boundaryResponse);

    expect(encodedByteLength(boundaryEnvelope)).toBe(APPLICATION_MAXIMUM_BYTES);
    expect(payload(boundaryEnvelope)["structuredViews"]).toHaveLength(2);

    const oversizedResponse = completion("ok", [
      view(FIRST_VIEW_ID, firstText),
      view(SECOND_VIEW_ID, "x".repeat(secondTextLength + 1)),
    ]);
    const fallbackEnvelope = protocol().createResponse(REQUEST_ID, oversizedResponse);

    expect(payload(fallbackEnvelope)).toMatchObject({
      fallbackText: "ok",
      structuredViews: [],
    });
    expect(encodedByteLength(fallbackEnvelope)).toBeLessThan(APPLICATION_MAXIMUM_BYTES);
  });
});
