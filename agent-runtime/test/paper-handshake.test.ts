import { rm } from "node:fs/promises";

import { afterEach, describe, expect, it, vi } from "vitest";
import WebSocket, { type RawData } from "ws";

import {
  bootstrap,
  startRuntime,
  type BootstrapResult,
  type StartRuntimeResult,
} from "../src/bootstrap/index.js";
import type { ModelProviderHealthCheck } from "../src/health/model-provider.js";
import { RuntimeLogger } from "../src/observability/runtime-logger.js";
import { SchemaRegistry } from "../src/protocol/schema-registry.js";
import {
  createHandshakeProof,
  verifyHandshakeProof,
  type HandshakeProofFields,
} from "../src/transport/handshake-authentication.js";
import { HANDSHAKE_MAXIMUM_BYTES } from "../src/transport/paper-handshake.js";
import {
  findAvailablePort,
  runtimeEnvironment,
  temporaryRuntimeDirectory,
  TEST_SERVER_TOKEN,
  validRuntimeConfig,
  writeRuntimeConfig,
} from "./helpers/runtime-fixture.js";

const NOW = new Date("2026-07-12T00:00:00.000Z");
const clients = new Set<WebSocket>();
const runtimes: (BootstrapResult | StartRuntimeResult)[] = [];
const temporaryDirectories: string[] = [];
let nextUuidSuffix = 1;

interface PaperHelloOptions {
  readonly token?: string;
  readonly timestamp?: string;
  readonly messageId?: string;
  readonly requestId?: string;
  readonly nonce?: string;
  readonly challenge?: string;
}

interface CloseDetails {
  readonly code: number;
  readonly reason: string;
}

function healthyProvider(): ModelProviderHealthCheck {
  return { check: vi.fn().mockResolvedValue({ ok: true }) };
}

function uuid(): string {
  const suffix = String(nextUuidSuffix).padStart(12, "0");
  nextUuidSuffix += 1;
  return `00000000-0000-4000-8000-${suffix}`;
}

function paperHello(options: PaperHelloOptions = {}): Record<string, unknown> {
  const messageId = options.messageId ?? uuid();
  const timestamp = options.timestamp ?? NOW.toISOString();
  const nonce = options.nonce ?? Buffer.alloc(16, nextUuidSuffix).toString("base64url");
  const challenge = options.challenge ?? Buffer.alloc(16, 0x21).toString("base64url");
  const fields: HandshakeProofFields = {
    serverId: "test-server",
    type: "paper.hello",
    timestamp,
    nonce,
    component: "paper",
    componentVersion: "0.1.0",
    challenge,
  };

  return {
    protocolVersion: "1.0",
    messageId,
    requestId: options.requestId ?? messageId,
    serverId: "test-server",
    type: "paper.hello",
    timestamp,
    nonce,
    payload: {
      component: "paper",
      componentVersion: "0.1.0",
      supportedProtocolVersions: ["1.0"],
      selectedProtocolVersion: null,
      authentication: {
        scheme: "hmac-sha256",
        keyId: "test-server",
        challenge,
        proof: createHandshakeProof(options.token ?? TEST_SERVER_TOKEN, fields),
      },
    },
  };
}

async function createConfig(port: number): Promise<string> {
  const directory = await temporaryRuntimeDirectory();
  temporaryDirectories.push(directory);
  return writeRuntimeConfig(directory, validRuntimeConfig(port));
}

async function startFixture(options: { readonly logger?: RuntimeLogger } = {}): Promise<{
  readonly runtime: StartRuntimeResult;
  readonly url: string;
}> {
  const port = await findAvailablePort();
  const configPath = await createConfig(port);
  const runtime = await startRuntime({
    configPath,
    environment: runtimeEnvironment(),
    modelProviderHealthCheck: healthyProvider(),
    now: () => NOW,
    ...(options.logger === undefined ? {} : { logger: options.logger }),
  });
  runtimes.push(runtime);
  return { runtime, url: `ws://127.0.0.1:${String(port)}/agent` };
}

async function openClient(url: string): Promise<WebSocket> {
  const socket = new WebSocket(url, { perMessageDeflate: false });
  clients.add(socket);
  socket.once("close", () => clients.delete(socket));
  await new Promise<void>((resolve, reject) => {
    socket.once("open", resolve);
    socket.once("error", reject);
  });
  return socket;
}

function nextMessage(socket: WebSocket): Promise<unknown> {
  return new Promise((resolve, reject) => {
    socket.once("error", reject);
    socket.once("message", (data: RawData, isBinary: boolean) => {
      if (isBinary) {
        reject(new Error("Expected a text WebSocket message"));
        return;
      }
      resolve(JSON.parse(Buffer.isBuffer(data) ? data.toString("utf8") : String(data)) as unknown);
    });
  });
}

function nextClose(socket: WebSocket): Promise<CloseDetails> {
  return new Promise((resolve) => {
    socket.once("close", (code, reason) => {
      resolve({ code, reason: reason.toString("utf8") });
    });
  });
}

async function exchange(socket: WebSocket, message: unknown): Promise<unknown> {
  const response = nextMessage(socket);
  socket.send(JSON.stringify(message));
  return response;
}

async function sendAndClose(
  socket: WebSocket,
  message: string | Buffer,
  binary = false,
): Promise<CloseDetails> {
  const closed = nextClose(socket);
  socket.send(message, { binary });
  return closed;
}

function asRecord(value: unknown): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new TypeError("Expected an object");
  }
  return value as Record<string, unknown>;
}

afterEach(async () => {
  for (const client of clients) {
    client.terminate();
  }
  clients.clear();
  await Promise.allSettled(runtimes.splice(0).map((runtime) => runtime.app.close()));
  await Promise.all(
    temporaryDirectories
      .splice(0)
      .map((directory) => rm(directory, { recursive: true, force: true })),
  );
});

describe("Paper WebSocket handshake", () => {
  it("returns an authenticated runtime.hello only after Runtime readiness", async () => {
    const lines: string[] = [];
    const logger = new RuntimeLogger({
      now: () => NOW,
      sink: { write: (line) => lines.push(line) },
    });
    const { runtime, url } = await startFixture({ logger });
    const socket = await openClient(url);
    const hello = paperHello();
    const response = asRecord(await exchange(socket, hello));
    const payload = asRecord(response["payload"]);
    const authentication = asRecord(payload["authentication"]);
    const requestPayload = asRecord(asRecord(hello["payload"])["authentication"]);

    const registry = await SchemaRegistry.load();
    expect(registry.validate("envelope.schema.json", response).valid).toBe(true);
    expect(registry.validate("handshake.schema.json", payload).valid).toBe(true);
    expect(response).toMatchObject({
      protocolVersion: "1.0",
      requestId: hello["requestId"],
      serverId: "test-server",
      type: "runtime.hello",
    });
    expect(payload).toMatchObject({
      component: "runtime",
      componentVersion: "0.1.0",
      supportedProtocolVersions: ["1.0"],
      selectedProtocolVersion: "1.0",
    });
    expect(authentication["challenge"]).toBe(requestPayload["challenge"]);
    expect(
      verifyHandshakeProof(
        TEST_SERVER_TOKEN,
        {
          serverId: String(response["serverId"]),
          type: "runtime.hello",
          timestamp: String(response["timestamp"]),
          nonce: String(response["nonce"]),
          component: "runtime",
          componentVersion: String(payload["componentVersion"]),
          challenge: String(authentication["challenge"]),
        },
        String(authentication["proof"]),
      ),
    ).toBe(true);

    const health = await fetch(
      `http://127.0.0.1:${String(runtime.listenAddress.port)}/health`,
    ).then((responseValue) => responseValue.text());
    const diagnostics = `${health}\n${lines.join("\n")}`;
    expect(diagnostics).not.toContain(TEST_SERVER_TOKEN);
    expect(diagnostics).not.toContain(String(authentication["proof"]));
  });

  it("allows one authenticated Paper and retains replay state across connections", async () => {
    const { url } = await startFixture();
    const firstHello = paperHello();
    const first = await openClient(url);
    await exchange(first, firstHello);

    const competing = await openClient(url);
    expect(await sendAndClose(competing, JSON.stringify(paperHello()))).toEqual({
      code: 1008,
      reason: "PAPER_ALREADY_CONNECTED",
    });

    const firstClosed = nextClose(first);
    first.close();
    await firstClosed;

    const replay = await openClient(url);
    expect(await sendAndClose(replay, JSON.stringify(firstHello))).toEqual({
      code: 1008,
      reason: "HANDSHAKE_REPLAYED",
    });

    const replacement = await openClient(url);
    expect(asRecord(await exchange(replacement, paperHello()))["type"]).toBe("runtime.hello");
  });

  it("rejects invalid handshakes without occupying the authenticated slot", async () => {
    const { url } = await startFixture();

    const wrongToken = await openClient(url);
    expect(
      await sendAndClose(
        wrongToken,
        JSON.stringify(paperHello({ token: "wrong-public-test-token-with-32-characters" })),
      ),
    ).toEqual({ code: 1008, reason: "AUTHENTICATION_FAILED" });

    const stale = await openClient(url);
    expect(
      await sendAndClose(
        stale,
        JSON.stringify(paperHello({ timestamp: "2026-07-11T23:59:29.999Z" })),
      ),
    ).toEqual({ code: 1008, reason: "HANDSHAKE_STALE" });

    const incompatibleHello = paperHello();
    incompatibleHello["protocolVersion"] = "2.0";
    const incompatible = await openClient(url);
    expect(await sendAndClose(incompatible, JSON.stringify(incompatibleHello))).toEqual({
      code: 1008,
      reason: "PROTOCOL_INCOMPATIBLE",
    });

    const unrelatedHello = paperHello({ requestId: uuid() });
    const unrelated = await openClient(url);
    expect(await sendAndClose(unrelated, JSON.stringify(unrelatedHello))).toEqual({
      code: 1008,
      reason: "HANDSHAKE_INVALID",
    });

    const selectedHello = paperHello();
    asRecord(selectedHello["payload"])["selectedProtocolVersion"] = "1.0";
    const selected = await openClient(url);
    expect(await sendAndClose(selected, JSON.stringify(selectedHello))).toEqual({
      code: 1008,
      reason: "HANDSHAKE_INVALID",
    });

    const unsupportedHello = paperHello();
    unsupportedHello["type"] = "health.ping";
    const unsupported = await openClient(url);
    expect(await sendAndClose(unsupported, JSON.stringify(unsupportedHello))).toEqual({
      code: 1008,
      reason: "UNSUPPORTED_MESSAGE_TYPE",
    });

    const valid = await openClient(url);
    expect(asRecord(await exchange(valid, paperHello()))["type"]).toBe("runtime.hello");
  });

  it("rejects duplicate-key JSON, binary input, and payloads over 16 KiB", async () => {
    const { url } = await startFixture();
    const source = JSON.stringify(paperHello());
    const duplicateSource = source.replace(
      '{"protocolVersion":"1.0",',
      '{"protocolVersion":"1.0","protocolVersion":"1.0",',
    );

    const duplicate = await openClient(url);
    expect(await sendAndClose(duplicate, duplicateSource)).toEqual({
      code: 1008,
      reason: "HANDSHAKE_INVALID",
    });

    const binary = await openClient(url);
    expect(await sendAndClose(binary, Buffer.from(source), true)).toEqual({
      code: 1008,
      reason: "HANDSHAKE_INVALID",
    });

    const invalidUtf8 = await openClient(url);
    expect((await sendAndClose(invalidUtf8, Buffer.from([0xc3, 0x28]))).code).toBe(1007);

    const oversized = await openClient(url);
    const oversizedClose = await sendAndClose(
      oversized,
      `"${"x".repeat(HANDSHAKE_MAXIMUM_BYTES)}"`,
    );
    expect(oversizedClose.code).toBe(1009);

    const valid = await openClient(url);
    expect(asRecord(await exchange(valid, paperHello()))["type"]).toBe("runtime.hello");
  });

  it("closes the authenticated connection when the Runtime stops", async () => {
    const { runtime, url } = await startFixture();
    const socket = await openClient(url);
    await exchange(socket, paperHello());
    const closed = nextClose(socket);

    await runtime.close();

    expect((await closed).code).not.toBe(1006);
    expect(socket.readyState).toBe(WebSocket.CLOSED);
    expect(runtime.health.view().status).toBe("STOPPED");
  });

  it("keeps /agent closed while the app is still STARTING", async () => {
    const port = await findAvailablePort();
    const configPath = await createConfig(port);
    const result = await bootstrap({
      configPath,
      environment: runtimeEnvironment(),
      modelProviderHealthCheck: healthyProvider(),
      now: () => NOW,
    });
    runtimes.push(result);
    await result.app.listen(result.listenAddress);

    const socket = await openClient(`ws://127.0.0.1:${String(port)}/agent`);
    expect(await nextClose(socket)).toEqual({ code: 1008, reason: "RUNTIME_NOT_READY" });
  });
});
