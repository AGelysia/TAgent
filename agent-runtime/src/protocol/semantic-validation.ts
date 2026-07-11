import { createHash } from "node:crypto";
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
  if (!isRecord(value) || !isRecord(value["execution"]) || !isRecord(value["arguments"])) {
    return [
      semanticError(
        "CAPABILITY_STRUCTURE_INVALID",
        rule,
        "",
        "capability manifest must contain execution and arguments objects",
      ),
    ];
  }

  const execution = value["execution"];
  const argumentsRecord = value["arguments"];
  const template = execution["template"];
  const commandRoot = execution["commandRoot"];

  if (typeof template === "string") {
    const placeholderPattern = /\{([a-z][a-zA-Z\d_]*)\}/gu;
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
    case "view-negotiation-v1":
      errors = validateViewNegotiation(value);
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

    if (rule === "view-negotiation-v1") {
      errors.push(...validateViewNegotiation(value));
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
