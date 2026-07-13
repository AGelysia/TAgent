import { createHash, createHmac, timingSafeEqual } from "node:crypto";
import { gunzipSync } from "node:zlib";

import { SUPPORTED_PROTOCOL_VERSION } from "../version.js";

export interface SemanticValidationError {
  readonly code: string;
  readonly rule: string;
  readonly instancePath: string;
  readonly message: string;
}

export interface SemanticValidationResult {
  readonly valid: boolean;
  readonly errors: readonly SemanticValidationError[];
}

type JsonRecord = Record<string, unknown>;

interface HandshakeGoldenSide {
  readonly protocolVersion: string;
  readonly messageId: string;
  readonly requestId: string;
  readonly serverId: string;
  readonly type: string;
  readonly timestamp: string;
  readonly nonce: string;
  readonly component: string;
  readonly componentVersion: string;
  readonly supportedProtocolVersions: readonly unknown[];
  readonly selectedProtocolVersion: unknown;
  readonly scheme: string;
  readonly keyId: string;
  readonly challenge: string;
  readonly proof: string;
}

interface ChunkDescriptor {
  readonly record: JsonRecord;
  readonly path: string;
  readonly index: number;
  readonly encodedData: string;
}

interface ChunkCollection {
  readonly chunks: readonly ChunkDescriptor[];
  readonly metadataRecords: readonly JsonRecord[];
  readonly path: string;
}

const INDEX_FIELDS = ["chunkIndex", "index"] as const;
const DATA_FIELDS = ["data", "dataBase64", "chunkData", "payloadBase64"] as const;
const CHUNK_COUNT_FIELDS = ["chunkCount", "totalChunks"] as const;
const CHUNK_BYTES_FIELDS = ["chunkBytes", "byteLength"] as const;
const CHUNK_HASH_FIELDS = ["chunkSha256", "sha256"] as const;
const TOTAL_COMPRESSED_BYTES_FIELDS = [
  "totalCompressedBytes",
  "compressedBytes",
  "totalBytes",
] as const;
const TOTAL_UNCOMPRESSED_BYTES_FIELDS = ["totalUncompressedBytes", "uncompressedBytes"] as const;
const CONTENT_HASH_FIELDS = ["contentSha256", "contentHash", "sha256"] as const;
const PROPOSAL_ARGUMENT_HASH_DOMAIN = "minecraft-agent/proposal-arguments/v1";
const PROPOSAL_ARGUMENT_CANONICAL_LIMIT_BYTES = 65_536;
const PROPOSAL_ARGUMENT_NODE_LIMIT = 4_096;
const PROPOSAL_ARGUMENT_DEPTH_LIMIT = 32;
const CAPABILITY_PLUGIN_VERSION_RANGE =
  /^(?:=|>=|>|<=|<)(?:0|[1-9][0-9]*)(?:\.(?:0|[1-9][0-9]*)){0,2}(?: (?:=|>=|>|<=|<)(?:0|[1-9][0-9]*)(?:\.(?:0|[1-9][0-9]*)){0,2})*$/u;
const CAPABILITY_PLUGIN_VERSION = /^(?:0|[1-9][0-9]*)(?:\.(?:0|[1-9][0-9]*)){0,2}$/u;
const CAPABILITY_PLUGIN_COMPARISON =
  /^(>=|<=|=|>|<)((?:0|[1-9][0-9]*)(?:\.(?:0|[1-9][0-9]*)){0,2})$/u;

export const BUILD_PREVIEW_UNCOMPRESSED_HARD_LIMIT_BYTES = 64 * 1024 * 1024;
export const BUILD_PREVIEW_COMPRESSED_HARD_LIMIT_BYTES = 16 * 1024 * 1024;

function isRecord(value: unknown): value is JsonRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function escapeJsonPointer(value: string): string {
  return value.replaceAll("~", "~0").replaceAll("/", "~1");
}

function childPath(parent: string, child: string | number): string {
  return `${parent}/${escapeJsonPointer(String(child))}`;
}

function semanticError(
  code: string,
  rule: string,
  instancePath: string,
  message: string,
): SemanticValidationError {
  return { code, rule, instancePath, message };
}

function readHandshakeGoldenSide(value: unknown): HandshakeGoldenSide | undefined {
  if (!isRecord(value) || !isRecord(value["payload"])) {
    return undefined;
  }
  const payload = value["payload"];
  if (!isRecord(payload["authentication"])) {
    return undefined;
  }
  const authentication = payload["authentication"];
  const strings = [
    value["protocolVersion"],
    value["messageId"],
    value["requestId"],
    value["serverId"],
    value["type"],
    value["timestamp"],
    value["nonce"],
    payload["component"],
    payload["componentVersion"],
    authentication["scheme"],
    authentication["keyId"],
    authentication["challenge"],
    authentication["proof"],
  ];
  if (strings.some((field) => typeof field !== "string")) {
    return undefined;
  }
  if (!Array.isArray(payload["supportedProtocolVersions"])) {
    return undefined;
  }

  return {
    protocolVersion: value["protocolVersion"] as string,
    messageId: value["messageId"] as string,
    requestId: value["requestId"] as string,
    serverId: value["serverId"] as string,
    type: value["type"] as string,
    timestamp: value["timestamp"] as string,
    nonce: value["nonce"] as string,
    component: payload["component"] as string,
    componentVersion: payload["componentVersion"] as string,
    supportedProtocolVersions: payload["supportedProtocolVersions"],
    selectedProtocolVersion: payload["selectedProtocolVersion"],
    scheme: authentication["scheme"] as string,
    keyId: authentication["keyId"] as string,
    challenge: authentication["challenge"] as string,
    proof: authentication["proof"] as string,
  };
}

function decodeCanonicalBase64Url(value: string, expectedBytes?: number): Buffer | undefined {
  if (!/^[A-Za-z\d_-]+$/u.test(value)) {
    return undefined;
  }
  const decoded = Buffer.from(value, "base64url");
  if (
    decoded.toString("base64url") !== value ||
    (expectedBytes === undefined ? decoded.length < 16 : decoded.length !== expectedBytes)
  ) {
    return undefined;
  }
  return decoded;
}

function handshakeTranscript(side: HandshakeGoldenSide): string {
  return [
    "minecraft-agent-handshake-v1",
    side.serverId,
    side.type,
    side.timestamp,
    side.nonce,
    side.component,
    side.componentVersion,
    side.challenge,
  ].join("\n");
}

function handshakeProofMatches(token: string, side: HandshakeGoldenSide): boolean {
  const suppliedProof = decodeCanonicalBase64Url(side.proof, 32);
  if (suppliedProof === undefined) {
    return false;
  }
  const expectedProof = createHmac("sha256", token)
    .update(handshakeTranscript(side), "utf8")
    .digest();
  return timingSafeEqual(suppliedProof, expectedProof);
}

export function validateHandshakeProof(value: unknown): SemanticValidationError[] {
  const rule = "handshake-proof-v1";
  if (!isRecord(value) || typeof value["publicTestToken"] !== "string") {
    return [
      semanticError(
        "HANDSHAKE_GOLDEN_STRUCTURE_INVALID",
        rule,
        "",
        "golden exchange must contain a public test token",
      ),
    ];
  }
  const paper = readHandshakeGoldenSide(value["paper"]);
  const runtime = readHandshakeGoldenSide(value["runtime"]);
  if (paper === undefined || runtime === undefined) {
    return [
      semanticError(
        "HANDSHAKE_GOLDEN_STRUCTURE_INVALID",
        rule,
        "",
        "golden exchange must contain complete Paper and Runtime hello envelopes",
      ),
    ];
  }

  if (
    paper.protocolVersion !== SUPPORTED_PROTOCOL_VERSION ||
    runtime.protocolVersion !== SUPPORTED_PROTOCOL_VERSION ||
    paper.type !== "paper.hello" ||
    runtime.type !== "runtime.hello" ||
    paper.component !== "paper" ||
    runtime.component !== "runtime" ||
    paper.scheme !== "hmac-sha256" ||
    runtime.scheme !== "hmac-sha256"
  ) {
    return [
      semanticError(
        "HANDSHAKE_IDENTITY_INVALID",
        rule,
        "",
        "hello direction, component identity, scheme, or protocol version is invalid",
      ),
    ];
  }
  if (
    paper.messageId !== paper.requestId ||
    runtime.requestId !== paper.requestId ||
    runtime.messageId === paper.messageId ||
    runtime.serverId !== paper.serverId ||
    paper.keyId !== paper.serverId ||
    runtime.keyId !== paper.serverId
  ) {
    return [
      semanticError(
        "HANDSHAKE_CORRELATION_INVALID",
        rule,
        "",
        "response correlation or configured server identity does not match the request",
      ),
    ];
  }
  if (
    paper.supportedProtocolVersions.length !== 1 ||
    paper.supportedProtocolVersions[0] !== SUPPORTED_PROTOCOL_VERSION ||
    runtime.supportedProtocolVersions.length !== 1 ||
    runtime.supportedProtocolVersions[0] !== SUPPORTED_PROTOCOL_VERSION ||
    paper.selectedProtocolVersion !== null ||
    runtime.selectedProtocolVersion !== SUPPORTED_PROTOCOL_VERSION
  ) {
    return [
      semanticError(
        "HANDSHAKE_NEGOTIATION_INVALID",
        rule,
        "",
        "Runtime must select the one protocol version advertised by Paper",
      ),
    ];
  }
  if (runtime.challenge !== paper.challenge) {
    return [
      semanticError(
        "HANDSHAKE_CHALLENGE_MISMATCH",
        rule,
        "/runtime/payload/authentication/challenge",
        "Runtime must echo Paper's challenge",
      ),
    ];
  }
  if (
    paper.nonce === runtime.nonce ||
    decodeCanonicalBase64Url(paper.nonce) === undefined ||
    decodeCanonicalBase64Url(runtime.nonce) === undefined ||
    decodeCanonicalBase64Url(paper.challenge) === undefined ||
    decodeCanonicalBase64Url(paper.proof, 32) === undefined ||
    decodeCanonicalBase64Url(runtime.proof, 32) === undefined
  ) {
    return [
      semanticError(
        "HANDSHAKE_BASE64URL_INVALID",
        rule,
        "",
        "nonces, challenge, and proofs must be distinct where required and canonical unpadded base64url",
      ),
    ];
  }
  if (
    !handshakeProofMatches(value["publicTestToken"], paper) ||
    !handshakeProofMatches(value["publicTestToken"], runtime)
  ) {
    return [
      semanticError(
        "HANDSHAKE_PROOF_INVALID",
        rule,
        "",
        "one or more handshake proofs do not match the fixed transcript",
      ),
    ];
  }
  return [];
}

function collectRecords(value: unknown, maximumDepth = 3): JsonRecord[] {
  const records: JsonRecord[] = [];

  function visit(candidate: unknown, depth: number): void {
    if (!isRecord(candidate) || depth > maximumDepth) {
      return;
    }
    records.push(candidate);
    for (const nested of Object.values(candidate)) {
      if (isRecord(nested)) {
        visit(nested, depth + 1);
      }
    }
  }

  visit(value, 0);
  return records;
}

function fieldValue(
  records: readonly JsonRecord[],
  fieldNames: readonly string[],
): { readonly name: string; readonly value: unknown } | undefined {
  for (const record of records) {
    for (const name of fieldNames) {
      if (Object.hasOwn(record, name)) {
        return { name, value: record[name] };
      }
    }
  }
  return undefined;
}

function numberField(
  records: readonly JsonRecord[],
  fieldNames: readonly string[],
): number | undefined {
  const field = fieldValue(records, fieldNames);
  return typeof field?.value === "number" && Number.isSafeInteger(field.value)
    ? field.value
    : undefined;
}

function stringField(
  records: readonly JsonRecord[],
  fieldNames: readonly string[],
): string | undefined {
  const field = fieldValue(records, fieldNames);
  return typeof field?.value === "string" ? field.value : undefined;
}

function findChunkDescriptor(value: unknown, path: string): ChunkDescriptor | undefined {
  for (const record of collectRecords(value)) {
    const index = numberField([record], INDEX_FIELDS);
    const data = stringField([record], DATA_FIELDS);
    if (index !== undefined && data !== undefined) {
      return {
        record,
        path,
        index,
        encodedData: data,
      };
    }
  }
  return undefined;
}

function findChunkCollections(value: unknown): ChunkCollection[] {
  const collections: ChunkCollection[] = [];

  function visit(candidate: unknown, path: string, ancestors: readonly JsonRecord[]): void {
    if (Array.isArray(candidate)) {
      const descriptors = candidate.flatMap((item, index) => {
        const descriptor = findChunkDescriptor(item, childPath(path, index));
        return descriptor === undefined ? [] : [descriptor];
      });
      const nonChunkRecords = candidate.flatMap((item) =>
        findChunkDescriptor(item, path) === undefined ? collectRecords(item) : [],
      );

      if (
        descriptors.length > 0 &&
        (descriptors.length === candidate.length || nonChunkRecords.length > 0)
      ) {
        collections.push({
          chunks: descriptors,
          metadataRecords: [
            ...ancestors,
            ...nonChunkRecords,
            ...descriptors.map(({ record }) => record),
          ],
          path,
        });
        return;
      }

      candidate.forEach((item, index) => visit(item, childPath(path, index), ancestors));
      return;
    }

    if (!isRecord(candidate)) {
      return;
    }

    const nextAncestors = [...collectRecords(candidate, 1), ...ancestors];
    for (const [name, nested] of Object.entries(candidate)) {
      visit(nested, childPath(path, name), nextAncestors);
    }
  }

  visit(value, "", []);
  return collections;
}

function decodeCanonicalBase64(value: string): Buffer | undefined {
  if (
    value.length % 4 !== 0 ||
    !/^(?:[A-Za-z\d+/]{4})*(?:[A-Za-z\d+/]{2}==|[A-Za-z\d+/]{3}=)?$/u.test(value)
  ) {
    return undefined;
  }

  const decoded = Buffer.from(value, "base64");
  return decoded.toString("base64") === value ? decoded : undefined;
}

function sha256(value: Buffer | string): string {
  return createHash("sha256").update(value).digest("hex");
}

function hasValidUnicode(value: string): boolean {
  for (let index = 0; index < value.length; index += 1) {
    const codeUnit = value.charCodeAt(index);
    if (codeUnit >= 0xd800 && codeUnit <= 0xdbff) {
      const next = value.charCodeAt(index + 1);
      if (next < 0xdc00 || next > 0xdfff) {
        return false;
      }
      index += 1;
    } else if (codeUnit >= 0xdc00 && codeUnit <= 0xdfff) {
      return false;
    }
  }
  return true;
}

function canonicalizeJson(value: unknown): string | undefined {
  const budget = { nodes: 0, textBytes: 0 };

  function encode(candidate: unknown, depth: number): string | undefined {
    budget.nodes += 1;
    if (depth > PROPOSAL_ARGUMENT_DEPTH_LIMIT || budget.nodes > PROPOSAL_ARGUMENT_NODE_LIMIT) {
      return undefined;
    }
    if (candidate === null) {
      return "null";
    }
    if (typeof candidate === "boolean") {
      return candidate ? "true" : "false";
    }
    if (typeof candidate === "number") {
      return Number.isFinite(candidate) ? JSON.stringify(candidate) : undefined;
    }
    if (typeof candidate === "string") {
      budget.textBytes += Buffer.byteLength(candidate, "utf8");
      if (
        !hasValidUnicode(candidate) ||
        budget.textBytes > PROPOSAL_ARGUMENT_CANONICAL_LIMIT_BYTES
      ) {
        return undefined;
      }
      return JSON.stringify(candidate);
    }
    if (Array.isArray(candidate)) {
      const encoded: string[] = [];
      for (let index = 0; index < candidate.length; index += 1) {
        if (!Object.hasOwn(candidate, index)) {
          return undefined;
        }
        const item = encode(candidate[index], depth + 1);
        if (item === undefined) {
          return undefined;
        }
        encoded.push(item);
      }
      return `[${encoded.join(",")}]`;
    }
    if (!isRecord(candidate)) {
      return undefined;
    }
    const prototype = Object.getPrototypeOf(candidate) as unknown;
    if (prototype !== Object.prototype && prototype !== null) {
      return undefined;
    }

    const encoded: string[] = [];
    for (const key of Object.keys(candidate).sort()) {
      budget.textBytes += Buffer.byteLength(key, "utf8");
      if (!hasValidUnicode(key) || budget.textBytes > PROPOSAL_ARGUMENT_CANONICAL_LIMIT_BYTES) {
        return undefined;
      }
      const item = encode(candidate[key], depth + 1);
      if (item === undefined) {
        return undefined;
      }
      encoded.push(`${JSON.stringify(key)}:${item}`);
    }
    return `{${encoded.join(",")}}`;
  }

  const canonical = encode(value, 0);
  return canonical !== undefined &&
    Buffer.byteLength(canonical, "utf8") <= PROPOSAL_ARGUMENT_CANONICAL_LIMIT_BYTES
    ? canonical
    : undefined;
}

export function validateProposalArgumentHash(value: unknown): SemanticValidationError[] {
  const rule = "proposal-argument-hash-v1";
  if (!isRecord(value) || !isRecord(value["hashContract"]) || !isRecord(value["proposal"])) {
    return [
      semanticError(
        "PROPOSAL_ARGUMENT_HASH_STRUCTURE_INVALID",
        rule,
        "",
        "proposal argument hash fixture is incomplete",
      ),
    ];
  }

  const hashContract = value["hashContract"];
  const proposal = value["proposal"];
  if (
    hashContract["algorithm"] !== "SHA-256" ||
    hashContract["domainUtf8"] !== PROPOSAL_ARGUMENT_HASH_DOMAIN ||
    hashContract["separatorHex"] !== "00" ||
    hashContract["canonicalization"] !== "RFC8785" ||
    !isRecord(proposal["arguments"])
  ) {
    return [
      semanticError(
        "PROPOSAL_ARGUMENT_HASH_CONTRACT_INVALID",
        rule,
        "/hashContract",
        "proposal argument hash contract metadata is invalid",
      ),
    ];
  }

  const canonical = canonicalizeJson(proposal["arguments"]);
  if (canonical === undefined) {
    return [
      semanticError(
        "PROPOSAL_ARGUMENT_CANONICALIZATION_INVALID",
        rule,
        "/proposal/arguments",
        "proposal arguments cannot be canonicalized as RFC 8785 JSON",
      ),
    ];
  }
  if (hashContract["canonicalArguments"] !== canonical) {
    return [
      semanticError(
        "PROPOSAL_ARGUMENT_CANONICAL_MISMATCH",
        rule,
        "/hashContract/canonicalArguments",
        "documented canonical arguments do not match the proposal",
      ),
    ];
  }

  const canonicalBytes = Buffer.from(canonical, "utf8");
  if (hashContract["canonicalUtf8ByteLength"] !== canonicalBytes.length) {
    return [
      semanticError(
        "PROPOSAL_ARGUMENT_CANONICAL_LENGTH_MISMATCH",
        rule,
        "/hashContract/canonicalUtf8ByteLength",
        "documented canonical argument length is incorrect",
      ),
    ];
  }

  const expectedHash = sha256(
    Buffer.concat([
      Buffer.from(PROPOSAL_ARGUMENT_HASH_DOMAIN, "utf8"),
      Buffer.from([0]),
      canonicalBytes,
    ]),
  );
  if (hashContract["argumentHash"] !== expectedHash || proposal["argumentHash"] !== expectedHash) {
    return [
      semanticError(
        "PROPOSAL_ARGUMENT_HASH_MISMATCH",
        rule,
        "/proposal/argumentHash",
        "proposal argument hash does not match the canonical arguments",
      ),
    ];
  }
  return [];
}

function decodeContent(
  compressedContent: Buffer,
  compression: string,
  declaredUncompressedBytes: number | undefined,
): { readonly content?: Buffer; readonly error?: string } {
  if (
    declaredUncompressedBytes === undefined ||
    declaredUncompressedBytes < 1 ||
    declaredUncompressedBytes > BUILD_PREVIEW_UNCOMPRESSED_HARD_LIMIT_BYTES
  ) {
    return {
      error: `declared uncompressed length must be between 1 and ${BUILD_PREVIEW_UNCOMPRESSED_HARD_LIMIT_BYTES} bytes`,
    };
  }

  try {
    switch (compression.toLowerCase()) {
      case "identity":
        return { content: compressedContent };
      case "gzip":
        return {
          content: gunzipSync(compressedContent, {
            maxOutputLength: Math.min(
              declaredUncompressedBytes,
              BUILD_PREVIEW_UNCOMPRESSED_HARD_LIMIT_BYTES,
            ),
          }),
        };
      default:
        return { error: `unsupported build preview encoding ${JSON.stringify(compression)}` };
    }
  } catch (error) {
    return {
      error: `unable to decompress transfer content: ${error instanceof Error ? error.message : String(error)}`,
    };
  }
}

export function validateProtocolVersion(
  value: unknown,
  supportedVersion = SUPPORTED_PROTOCOL_VERSION,
): SemanticValidationError[] {
  const errors: SemanticValidationError[] = [];
  const visited = new WeakSet<object>();

  function visit(candidate: unknown, path: string): void {
    if (typeof candidate !== "object" || candidate === null || visited.has(candidate)) {
      return;
    }
    visited.add(candidate);

    if (Array.isArray(candidate)) {
      candidate.forEach((item, index) => visit(item, childPath(path, index)));
      return;
    }

    for (const [name, nested] of Object.entries(candidate)) {
      const nestedPath = childPath(path, name);
      if (
        (name === "protocolVersion" || name === "clientProtocolVersion") &&
        nested !== supportedVersion
      ) {
        errors.push(
          semanticError(
            "PROTOCOL_VERSION_UNSUPPORTED",
            "protocol-version-compatible",
            nestedPath,
            `unsupported protocol version ${JSON.stringify(nested)}; expected ${JSON.stringify(supportedVersion)}`,
          ),
        );
      }
      visit(nested, nestedPath);
    }
  }

  visit(value, "");
  return errors;
}

export function validateBuildPreviewChunks(value: unknown): SemanticValidationError[] {
  const rule = "build-preview-transfer-v1";
  const collections = findChunkCollections(value);

  if (collections.length === 0) {
    return [
      semanticError("CHUNK_SET_INCOMPLETE", rule, "", "no transfer chunk collection was found"),
    ];
  }

  for (const collection of collections) {
    const expectedChunkCount = numberField(collection.metadataRecords, CHUNK_COUNT_FIELDS);
    const seenIndexes = new Set<number>();
    for (const chunk of collection.chunks) {
      if (seenIndexes.has(chunk.index)) {
        return [
          semanticError(
            "CHUNK_INDEX_DUPLICATE",
            rule,
            chunk.path,
            `chunkIndex ${chunk.index} is duplicated`,
          ),
        ];
      }
      seenIndexes.add(chunk.index);
    }

    const sortedIndexes = [...seenIndexes].sort((left, right) => left - right);
    if (
      expectedChunkCount === undefined ||
      expectedChunkCount !== collection.chunks.length ||
      sortedIndexes.some((index, position) => index !== position)
    ) {
      return [
        semanticError(
          "CHUNK_SET_INCOMPLETE",
          rule,
          collection.path,
          "chunk indexes must be exactly 0..chunkCount-1",
        ),
      ];
    }

    const totalCompressedBytes = numberField(
      collection.metadataRecords,
      TOTAL_COMPRESSED_BYTES_FIELDS,
    );
    if (
      totalCompressedBytes === undefined ||
      totalCompressedBytes < 1 ||
      totalCompressedBytes > BUILD_PREVIEW_COMPRESSED_HARD_LIMIT_BYTES
    ) {
      return [
        semanticError(
          "CONTENT_COMPRESSED_LENGTH_MISMATCH",
          rule,
          collection.path,
          "declared compressed length is missing or exceeds the hard limit",
        ),
      ];
    }

    const orderedChunks = [...collection.chunks].sort((left, right) => left.index - right.index);
    const decodedChunks: Buffer[] = [];
    let decodedByteCount = 0;
    for (const chunk of orderedChunks) {
      const chunkRecords = [chunk.record];
      const decoded = decodeCanonicalBase64(chunk.encodedData);
      if (decoded === undefined) {
        return [
          semanticError(
            "CHUNK_BASE64_INVALID",
            rule,
            chunk.path,
            "chunk data is not canonical base64",
          ),
        ];
      }
      decodedByteCount += decoded.length;
      if (
        decodedByteCount > totalCompressedBytes ||
        decodedByteCount > BUILD_PREVIEW_COMPRESSED_HARD_LIMIT_BYTES
      ) {
        return [
          semanticError(
            "CONTENT_COMPRESSED_LENGTH_MISMATCH",
            rule,
            chunk.path,
            "decoded chunks exceed the declared compressed length or hard limit",
          ),
        ];
      }
      decodedChunks.push(decoded);

      const declaredChunkBytes = numberField(chunkRecords, CHUNK_BYTES_FIELDS);
      if (declaredChunkBytes === undefined || declaredChunkBytes !== decoded.length) {
        return [
          semanticError(
            "CHUNK_LENGTH_MISMATCH",
            rule,
            chunk.path,
            `declared chunk byte length ${String(declaredChunkBytes)} does not match decoded length ${decoded.length}`,
          ),
        ];
      }

      const declaredChunkHash = stringField(chunkRecords, CHUNK_HASH_FIELDS);
      if (declaredChunkHash === undefined || sha256(decoded) !== declaredChunkHash.toLowerCase()) {
        return [
          semanticError(
            "CHUNK_HASH_MISMATCH",
            rule,
            chunk.path,
            "chunk sha256 does not match decoded chunk data",
          ),
        ];
      }
    }

    const compressedContent = Buffer.concat(decodedChunks, decodedByteCount);
    if (totalCompressedBytes !== compressedContent.length) {
      return [
        semanticError(
          "CONTENT_COMPRESSED_LENGTH_MISMATCH",
          rule,
          collection.path,
          `declared compressed length ${String(totalCompressedBytes)} does not match ${compressedContent.length} transfer bytes`,
        ),
      ];
    }

    const totalUncompressedBytes = numberField(
      collection.metadataRecords,
      TOTAL_UNCOMPRESSED_BYTES_FIELDS,
    );
    const compression = (
      stringField(collection.metadataRecords, ["compression", "encoding"]) ?? "identity"
    ).replace(/\+base64$/u, "");
    const decodedContent = decodeContent(compressedContent, compression, totalUncompressedBytes);
    if (decodedContent.error !== undefined || decodedContent.content === undefined) {
      return [
        semanticError(
          "CONTENT_DECOMPRESSION_FAILED",
          rule,
          collection.path,
          decodedContent.error ?? "unable to decode transfer content",
        ),
      ];
    }

    if (
      totalUncompressedBytes === undefined ||
      totalUncompressedBytes !== decodedContent.content.length
    ) {
      return [
        semanticError(
          "CONTENT_UNCOMPRESSED_LENGTH_MISMATCH",
          rule,
          collection.path,
          `declared uncompressed length ${String(totalUncompressedBytes)} does not match ${decodedContent.content.length} content bytes`,
        ),
      ];
    }

    const contentHash = stringField(collection.metadataRecords, CONTENT_HASH_FIELDS);
    if (contentHash === undefined || sha256(decodedContent.content) !== contentHash.toLowerCase()) {
      return [
        semanticError(
          "CONTENT_HASH_MISMATCH",
          rule,
          collection.path,
          "contentHash does not match reassembled uncompressed content",
        ),
      ];
    }
  }

  return [];
}

export function validateRecipeView(value: unknown): SemanticValidationError[] {
  const rule = "recipe-view-v1";
  if (!isRecord(value) || !Array.isArray(value["recipes"])) {
    return [
      semanticError(
        "RECIPE_VIEW_STRUCTURE_INVALID",
        rule,
        "",
        "recipe view must contain a recipes array",
      ),
    ];
  }

  const errors: SemanticValidationError[] = [];
  const recipes = value["recipes"];
  const selectedRecipe = value["selectedRecipe"];
  if (
    typeof selectedRecipe !== "number" ||
    !Number.isSafeInteger(selectedRecipe) ||
    selectedRecipe < 0 ||
    selectedRecipe >= recipes.length
  ) {
    errors.push(
      semanticError(
        "RECIPE_SELECTED_INDEX_OUT_OF_RANGE",
        rule,
        "/selectedRecipe",
        "selectedRecipe must identify an entry in recipes",
      ),
    );
  }

  const recipeIds = new Set<string>();
  recipes.forEach((recipe, recipeIndex) => {
    if (!isRecord(recipe)) {
      return;
    }
    const recipePath = `/recipes/${recipeIndex}`;
    const recipeId = recipe["recipeId"];
    if (typeof recipeId === "string") {
      if (recipeIds.has(recipeId)) {
        errors.push(
          semanticError(
            "RECIPE_ID_DUPLICATE",
            rule,
            `${recipePath}/recipeId`,
            `recipeId ${JSON.stringify(recipeId)} is duplicated`,
          ),
        );
      }
      recipeIds.add(recipeId);
    }

    const layout = recipe["layout"];
    if (!isRecord(layout) || !Array.isArray(layout["ingredients"])) {
      return;
    }
    const width = layout["width"];
    const height = layout["height"];
    if (typeof width !== "number" || typeof height !== "number") {
      return;
    }

    const slots = new Set<number>();
    const positions = new Set<string>();
    layout["ingredients"].forEach((ingredient, ingredientIndex) => {
      if (!isRecord(ingredient)) {
        return;
      }
      const ingredientPath = `${recipePath}/layout/ingredients/${ingredientIndex}`;
      const slot = ingredient["slot"];
      const x = ingredient["x"];
      const y = ingredient["y"];
      if (typeof x === "number" && typeof y === "number") {
        if (x < 0 || x >= width || y < 0 || y >= height) {
          errors.push(
            semanticError(
              "RECIPE_INGREDIENT_OUT_OF_BOUNDS",
              rule,
              ingredientPath,
              `ingredient position (${x}, ${y}) is outside ${width}x${height} layout`,
            ),
          );
        }
        const position = `${x},${y}`;
        if (positions.has(position)) {
          errors.push(
            semanticError(
              "RECIPE_INGREDIENT_DUPLICATE",
              rule,
              ingredientPath,
              `ingredient position (${x}, ${y}) is duplicated`,
            ),
          );
        }
        positions.add(position);
      }
      if (typeof slot === "number") {
        if (slots.has(slot)) {
          errors.push(
            semanticError(
              "RECIPE_INGREDIENT_DUPLICATE",
              rule,
              `${ingredientPath}/slot`,
              `ingredient slot ${slot} is duplicated`,
            ),
          );
        }
        slots.add(slot);
      }
      if (
        typeof slot === "number" &&
        typeof x === "number" &&
        typeof y === "number" &&
        slot !== y * width + x
      ) {
        errors.push(
          semanticError(
            "RECIPE_SLOT_COORDINATE_MISMATCH",
            rule,
            `${ingredientPath}/slot`,
            `ingredient slot ${slot} does not match row-major index ${y * width + x}`,
          ),
        );
      }
    });
  });

  return errors;
}

export function validateCapabilityManifest(value: unknown): SemanticValidationError[] {
  const rule = "capability-manifest-v1";
  if (
    !isRecord(value) ||
    !isRecord(value["requirements"]) ||
    !Array.isArray(value["requirements"]["plugins"]) ||
    !isRecord(value["execution"]) ||
    !isRecord(value["arguments"]) ||
    !isRecord(value["effects"]) ||
    !isRecord(value["confirmation"]) ||
    !isRecord(value["reversibility"])
  ) {
    return [
      semanticError(
        "CAPABILITY_STRUCTURE_INVALID",
        rule,
        "",
        "capability manifest is missing a required closed object",
      ),
    ];
  }

  const execution = value["execution"];
  const argumentsRecord = value["arguments"];
  const plugins = value["requirements"]["plugins"];
  const effects = value["effects"];
  const confirmation = value["confirmation"];
  const template = execution["template"];
  const commandRoot = execution["commandRoot"];

  if (typeof template === "string") {
    const placeholderPattern = /\{([a-z][a-zA-Z\d_]{0,63})\}/gu;
    const matches = [...template.matchAll(placeholderPattern)];
    if (/[{}]/u.test(template.replace(placeholderPattern, ""))) {
      return [
        semanticError(
          "CAPABILITY_TEMPLATE_PLACEHOLDER_MALFORMED",
          rule,
          "/execution/template",
          "template contains an incomplete or malformed placeholder",
        ),
      ];
    }

    for (const match of matches) {
      const start = match.index ?? -1;
      const end = start + match[0].length;
      if (
        start <= 0 ||
        template[start - 1] !== " " ||
        (end < template.length && template[end] !== " ")
      ) {
        return [
          semanticError(
            "CAPABILITY_TEMPLATE_PLACEHOLDER_MALFORMED",
            rule,
            "/execution/template",
            "template placeholders must occupy complete command tokens",
          ),
        ];
      }
    }

    const placeholderNames = matches.map((match) => match[1]!);
    for (const placeholder of placeholderNames) {
      if (!Object.hasOwn(argumentsRecord, placeholder)) {
        return [
          semanticError(
            "CAPABILITY_TEMPLATE_ARGUMENT_UNDECLARED",
            rule,
            "/execution/template",
            `template placeholder ${JSON.stringify(placeholder)} is not declared in arguments`,
          ),
        ];
      }
    }

    const seenPlaceholders = new Set<string>();
    for (const placeholder of placeholderNames) {
      if (seenPlaceholders.has(placeholder)) {
        return [
          semanticError(
            "CAPABILITY_TEMPLATE_ARGUMENT_DUPLICATE",
            rule,
            "/execution/template",
            `template placeholder ${JSON.stringify(placeholder)} appears more than once`,
          ),
        ];
      }
      seenPlaceholders.add(placeholder);
    }

    for (const [argumentName, argument] of Object.entries(argumentsRecord)) {
      if (
        isRecord(argument) &&
        argument["required"] === true &&
        !seenPlaceholders.has(argumentName)
      ) {
        return [
          semanticError(
            "CAPABILITY_REQUIRED_ARGUMENT_UNUSED",
            rule,
            `/arguments/${escapeJsonPointer(argumentName)}`,
            `required argument ${JSON.stringify(argumentName)} is not used by the template`,
          ),
        ];
      }
    }

    for (const [argumentName, argument] of Object.entries(argumentsRecord)) {
      if (isRecord(argument) && argument["required"] === false) {
        return [
          semanticError(
            "CAPABILITY_OPTIONAL_ARGUMENT_UNSUPPORTED",
            rule,
            `/arguments/${escapeJsonPointer(argumentName)}`,
            "optional command template arguments are not supported in capability v1",
          ),
        ];
      }
    }

    const rootMatch = /^\/([^\s{]+)/u.exec(template);
    if (typeof commandRoot === "string" && rootMatch?.[1] !== commandRoot) {
      return [
        semanticError(
          "CAPABILITY_COMMAND_ROOT_MISMATCH",
          rule,
          "/execution/commandRoot",
          "commandRoot does not match the first command token in template",
        ),
      ];
    }
  }

  for (const [argumentName, argument] of Object.entries(argumentsRecord)) {
    if (!isRecord(argument)) {
      continue;
    }
    const minimum = argument["minimum"] ?? argument["minLength"];
    const maximum = argument["maximum"] ?? argument["maxLength"];
    if (typeof minimum === "number" && typeof maximum === "number" && minimum > maximum) {
      return [
        semanticError(
          "CAPABILITY_ARGUMENT_RANGE_INVALID",
          rule,
          `/arguments/${escapeJsonPointer(argumentName)}`,
          `argument minimum ${minimum} exceeds maximum ${maximum}`,
        ),
      ];
    }
  }

  const pluginNames = new Set<string>();
  for (let index = 0; index < plugins.length; index += 1) {
    const plugin = plugins[index];
    if (!isRecord(plugin) || typeof plugin["name"] !== "string") {
      return [
        semanticError(
          "CAPABILITY_STRUCTURE_INVALID",
          rule,
          `/requirements/plugins/${index}`,
          "plugin requirement is not a closed name and version object",
        ),
      ];
    }
    const normalizedName = plugin["name"].toLowerCase();
    if (pluginNames.has(normalizedName)) {
      return [
        semanticError(
          "CAPABILITY_PLUGIN_REQUIREMENT_DUPLICATE",
          rule,
          `/requirements/plugins/${index}/name`,
          `plugin ${JSON.stringify(plugin["name"])} is declared more than once`,
        ),
      ];
    }
    pluginNames.add(normalizedName);
    if (
      typeof plugin["version"] !== "string" ||
      !CAPABILITY_PLUGIN_VERSION_RANGE.test(plugin["version"]) ||
      plugin["version"].split(" ").length > 16
    ) {
      return [
        semanticError(
          "CAPABILITY_PLUGIN_VERSION_RANGE_INVALID",
          rule,
          `/requirements/plugins/${index}/version`,
          "plugin version range must be an AND-list of closed numeric comparators",
        ),
      ];
    }
  }

  const effectCategory = effects["category"];
  const maximumBlocks = effects["maximumBlocks"];
  if (
    (effectCategory === "WRITE_WORLD" && typeof maximumBlocks !== "number") ||
    (effectCategory !== "WRITE_WORLD" && maximumBlocks !== null)
  ) {
    return [
      semanticError(
        "CAPABILITY_EFFECT_CONSTRAINT_INVALID",
        rule,
        "/effects/maximumBlocks",
        "only WRITE_WORLD requires a non-null maximumBlocks limit",
      ),
    ];
  }
  if (effectCategory !== "READ" && confirmation["required"] !== true) {
    return [
      semanticError(
        "CAPABILITY_CONFIRMATION_POLICY_INVALID",
        rule,
        "/confirmation/required",
        "every non-READ effect requires confirmation",
      ),
    ];
  }

  return [];
}

function capabilityPluginKey(manifest: JsonRecord): string | undefined {
  const requirements = manifest["requirements"];
  if (!isRecord(requirements) || !Array.isArray(requirements["plugins"])) {
    return undefined;
  }
  const entries: string[] = [];
  for (const plugin of requirements["plugins"]) {
    if (
      !isRecord(plugin) ||
      typeof plugin["name"] !== "string" ||
      typeof plugin["version"] !== "string"
    ) {
      return undefined;
    }
    entries.push(`${plugin["name"].toLowerCase()}\u0000${plugin["version"]}`);
  }
  return entries.sort().join("\u0001");
}

export function validateCapabilityPack(value: unknown): SemanticValidationError[] {
  const rule = "capability-pack-v1";
  if (!isRecord(value) || !Array.isArray(value["capabilities"])) {
    return [
      semanticError(
        "CAPABILITY_PACK_STRUCTURE_INVALID",
        rule,
        "",
        "capability pack golden must contain a capabilities array",
      ),
    ];
  }

  const capabilities = value["capabilities"];
  const byId = new Map<string, JsonRecord>();
  for (let index = 0; index < capabilities.length; index += 1) {
    const capability = capabilities[index];
    const manifestErrors = validateCapabilityManifest(capability);
    if (manifestErrors.length > 0) {
      const first = manifestErrors[0]!;
      return [
        {
          ...first,
          rule,
          instancePath: `/capabilities/${index}${first.instancePath}`,
        },
      ];
    }
    if (!isRecord(capability) || typeof capability["id"] !== "string") {
      return [
        semanticError(
          "CAPABILITY_PACK_STRUCTURE_INVALID",
          rule,
          `/capabilities/${index}`,
          "capability is missing its ID",
        ),
      ];
    }
    if (byId.has(capability["id"])) {
      return [
        semanticError(
          "CAPABILITY_PACK_ID_DUPLICATE",
          rule,
          `/capabilities/${index}/id`,
          `capability ID ${JSON.stringify(capability["id"])} is duplicated`,
        ),
      ];
    }
    byId.set(capability["id"], capability);
  }

  for (let index = 0; index < capabilities.length; index += 1) {
    const capability = capabilities[index]! as JsonRecord;
    const reversibility = capability["reversibility"] as JsonRecord;
    if (reversibility["type"] !== "capability") {
      continue;
    }
    const targetId = reversibility["capability"];
    const target = typeof targetId === "string" ? byId.get(targetId) : undefined;
    const sourceExecution = capability["execution"] as JsonRecord;
    const sourceEffects = capability["effects"] as JsonRecord;
    const targetExecution = target?.["execution"];
    const targetEffects = target?.["effects"];
    if (
      target === undefined ||
      target === capability ||
      target["status"] !== undefined ||
      !isRecord(targetExecution) ||
      !isRecord(targetEffects) ||
      targetExecution["source"] !== sourceExecution["source"] ||
      targetEffects["category"] !== sourceEffects["category"] ||
      targetEffects["scope"] !== sourceEffects["scope"] ||
      capabilityPluginKey(target) !== capabilityPluginKey(capability)
    ) {
      return [
        semanticError(
          "CAPABILITY_REVERSIBILITY_TARGET_INVALID",
          rule,
          `/capabilities/${index}/reversibility/capability`,
          "reversal target is missing, inactive, self-referential, or incompatible",
        ),
      ];
    }
  }
  return [];
}

function parseCapabilityVersion(value: string): [bigint, bigint, bigint] | undefined {
  if (value.length > 128 || !CAPABILITY_PLUGIN_VERSION.test(value)) {
    return undefined;
  }
  const components = value.split(".").map((component) => BigInt(component));
  return [components[0]!, components[1] ?? 0n, components[2] ?? 0n];
}

function compareCapabilityVersions(left: readonly bigint[], right: readonly bigint[]): number {
  for (let index = 0; index < 3; index += 1) {
    if (left[index]! < right[index]!) {
      return -1;
    }
    if (left[index]! > right[index]!) {
      return 1;
    }
  }
  return 0;
}

function capabilityVersionMatches(range: string, installed: readonly bigint[]): boolean {
  return range.split(" ").every((token) => {
    const comparison = CAPABILITY_PLUGIN_COMPARISON.exec(token);
    if (comparison === null) {
      return false;
    }
    const boundary = parseCapabilityVersion(comparison[2]!);
    if (boundary === undefined) {
      return false;
    }
    const order = compareCapabilityVersions(installed, boundary);
    switch (comparison[1]) {
      case "=":
        return order === 0;
      case ">":
        return order > 0;
      case ">=":
        return order >= 0;
      case "<":
        return order < 0;
      case "<=":
        return order <= 0;
      default:
        return false;
    }
  });
}

export function validateCapabilityPluginVersion(value: unknown): SemanticValidationError[] {
  const rule = "capability-plugin-version-v1";
  if (
    !isRecord(value) ||
    !isRecord(value["manifest"]) ||
    !Array.isArray(value["cases"]) ||
    value["cases"].length === 0
  ) {
    return [
      semanticError(
        "CAPABILITY_PLUGIN_VERSION_GOLDEN_STRUCTURE_INVALID",
        rule,
        "",
        "version golden must contain one manifest and a cases array",
      ),
    ];
  }
  const manifestErrors = validateCapabilityManifest(value["manifest"]);
  if (manifestErrors.length > 0) {
    return manifestErrors.map((error) => ({ ...error, rule }));
  }
  const requirements = value["manifest"]["requirements"];
  const plugins = isRecord(requirements) ? requirements["plugins"] : undefined;
  if (!Array.isArray(plugins) || plugins.length !== 1 || !isRecord(plugins[0])) {
    return [
      semanticError(
        "CAPABILITY_PLUGIN_VERSION_GOLDEN_STRUCTURE_INVALID",
        rule,
        "/manifest/requirements/plugins",
        "version golden manifest must contain exactly one plugin requirement",
      ),
    ];
  }
  const range = plugins[0]["version"];
  if (typeof range !== "string") {
    return [
      semanticError(
        "CAPABILITY_PLUGIN_VERSION_GOLDEN_STRUCTURE_INVALID",
        rule,
        "/manifest/requirements/plugins/0/version",
        "version golden range is missing",
      ),
    ];
  }

  for (let index = 0; index < value["cases"].length; index += 1) {
    const testCase = value["cases"][index];
    if (
      !isRecord(testCase) ||
      typeof testCase["installedVersion"] !== "string" ||
      !["match", "mismatch", "invalid"].includes(String(testCase["expected"]))
    ) {
      return [
        semanticError(
          "CAPABILITY_PLUGIN_VERSION_GOLDEN_STRUCTURE_INVALID",
          rule,
          `/cases/${index}`,
          "version golden case is malformed",
        ),
      ];
    }
    const installed = parseCapabilityVersion(testCase["installedVersion"]);
    const actual =
      installed === undefined
        ? "invalid"
        : capabilityVersionMatches(range, installed)
          ? "match"
          : "mismatch";
    if (actual !== testCase["expected"]) {
      return [
        semanticError(
          "CAPABILITY_PLUGIN_VERSION_GOLDEN_MISMATCH",
          rule,
          `/cases/${index}`,
          `expected ${String(testCase["expected"])} but computed ${actual}`,
        ),
      ];
    }
  }
  return [];
}

export function validateViewNegotiation(value: unknown): SemanticValidationError[] {
  const rule = "view-negotiation-v1";
  if (!isRecord(value) || !isRecord(value["client"]) || !isRecord(value["view"])) {
    return [
      semanticError(
        "VIEW_NEGOTIATION_STRUCTURE_INVALID",
        rule,
        "",
        "view negotiation must contain client and view objects",
      ),
    ];
  }

  const client = value["client"];
  const view = value["view"];
  if (client["clientProtocolVersion"] !== SUPPORTED_PROTOCOL_VERSION) {
    return [
      semanticError(
        "VIEW_PROTOCOL_UNSUPPORTED",
        rule,
        "/client/clientProtocolVersion",
        "client protocol version is not supported",
      ),
    ];
  }

  const requirements: Readonly<Record<string, readonly string[]>> = {
    text: ["overlay"],
    selection_list: ["overlay"],
    proposal: ["overlay"],
    item_stack: ["overlay", "itemIcons"],
    item_list: ["overlay", "itemIcons"],
    recipe: ["overlay", "itemIcons", "recipeView"],
    build_preview: ["overlay", "litematicaPreview"],
  };
  const capabilities = client["capabilities"];
  const requiredCapabilities =
    typeof view["viewType"] === "string" ? requirements[view["viewType"]] : undefined;
  if (!isRecord(capabilities) || requiredCapabilities === undefined) {
    return [
      semanticError(
        "VIEW_NEGOTIATION_STRUCTURE_INVALID",
        rule,
        "",
        "view type or client capabilities are invalid",
      ),
    ];
  }
  for (const capability of requiredCapabilities) {
    if (capabilities[capability] !== 1) {
      return [
        semanticError(
          "VIEW_CAPABILITY_UNDECLARED",
          rule,
          `/client/capabilities/${capability}`,
          `client did not declare ${capability} version 1`,
        ),
      ];
    }
  }
  return [];
}

export function runSemanticValidator(validator: string, value: unknown): SemanticValidationResult {
  let errors: SemanticValidationError[];
  switch (normalizeRule(validator)) {
    case "handshake-proof-v1":
      errors = validateHandshakeProof(value);
      break;
    case "recipe-view-v1":
      errors = validateRecipeView(value);
      break;
    case "build-preview-transfer-v1":
    case "reassemble-and-verify-sha256":
      errors = validateBuildPreviewChunks(value);
      break;
    case "capability-manifest-v1":
      errors = validateCapabilityManifest(value);
      break;
    case "capability-pack-v1":
      errors = validateCapabilityPack(value);
      break;
    case "capability-plugin-version-v1":
      errors = validateCapabilityPluginVersion(value);
      break;
    case "view-negotiation-v1":
      errors = validateViewNegotiation(value);
      break;
    case "proposal-argument-hash-v1":
      errors = validateProposalArgumentHash(value);
      break;
    case "protocol-version-compatible":
      errors = validateProtocolVersion(value);
      break;
    default:
      errors = [
        semanticError(
          "SEMANTIC_VALIDATOR_UNSUPPORTED",
          "semantic-validator-supported",
          "",
          `unsupported semantic validator ${JSON.stringify(validator)}`,
        ),
      ];
      break;
  }

  return { valid: errors.length === 0, errors };
}

function normalizeRule(rule: string): string {
  return rule
    .replace(/([a-z\d])([A-Z])/gu, "$1-$2")
    .replaceAll("_", "-")
    .trim()
    .toLowerCase();
}

export function validateContractSemantics(
  value: unknown,
  semanticRule?: string | readonly string[],
): SemanticValidationError[] {
  const errors = validateProtocolVersion(value);
  const rules =
    semanticRule === undefined ? [] : Array.isArray(semanticRule) ? semanticRule : [semanticRule];
  let chunksValidated = false;

  for (const originalRule of rules) {
    const rule = normalizeRule(originalRule);
    if (rule === "" || rule === "none" || rule.includes("protocol-version")) {
      continue;
    }

    if (rule === "proposal-argument-hash-v1") {
      errors.push(...validateProposalArgumentHash(value));
      continue;
    }

    if (
      rule === "build-preview-transfer-v1" ||
      rule === "reassemble-and-verify-sha256" ||
      rule.includes("chunk") ||
      rule.includes("base64") ||
      rule.includes("sha256")
    ) {
      if (!chunksValidated) {
        errors.push(...validateBuildPreviewChunks(value));
        chunksValidated = true;
      }
      continue;
    }

    if (rule === "recipe-view-v1") {
      errors.push(...validateRecipeView(value));
      continue;
    }

    if (rule === "capability-manifest-v1") {
      errors.push(...validateCapabilityManifest(value));
      continue;
    }

    if (rule === "capability-pack-v1") {
      errors.push(...validateCapabilityPack(value));
      continue;
    }

    if (rule === "capability-plugin-version-v1") {
      errors.push(...validateCapabilityPluginVersion(value));
      continue;
    }

    if (rule === "view-negotiation-v1") {
      errors.push(...validateViewNegotiation(value));
      continue;
    }

    if (rule === "handshake-proof-v1") {
      errors.push(...validateHandshakeProof(value));
      continue;
    }

    errors.push(
      semanticError(
        "SEMANTIC_VALIDATOR_UNSUPPORTED",
        "semantic-rule-supported",
        "",
        `unsupported semantic rule ${JSON.stringify(originalRule)}`,
      ),
    );
  }

  return errors;
}
