import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { gzipSync } from "node:zlib";

import { describe, expect, it } from "vitest";

import {
  validateBuildPreviewChunks,
  validateCapabilityManifest,
  validateContractSemantics,
  validateHandshakeProof,
  validateProposalArgumentHash,
  validateProtocolVersion,
  validateRecipeView,
} from "../src/protocol/semantic-validation.js";
import { defaultProtocolRoot } from "../src/protocol/schema-registry.js";

function sha256(value: Buffer): string {
  return createHash("sha256").update(value).digest("hex");
}

function validTransferFixture(): Record<string, unknown> {
  const content = Buffer.from('{"previewId":"preview-1","palette":["minecraft:stone"]}', "utf8");
  const compressed = gzipSync(content);
  const splitAt = Math.floor(compressed.length / 2);
  const pieces = [compressed.subarray(0, splitAt), compressed.subarray(splitAt)];

  return {
    begin: {
      transferId: "transfer-1",
      contentType: "build-preview",
      contentSchemaVersion: "1.0",
      compression: "gzip",
      chunkCount: pieces.length,
      totalCompressedBytes: compressed.length,
      totalUncompressedBytes: content.length,
      contentSha256: sha256(content),
    },
    chunks: pieces.map((piece, chunkIndex) => ({
      transferId: "transfer-1",
      chunkIndex,
      chunkCount: pieces.length,
      data: piece.toString("base64"),
      chunkBytes: piece.length,
      chunkSha256: sha256(piece),
    })),
  };
}

describe("protocol semantic validation", () => {
  it("locks the cross-language handshake transcript and response semantics", async () => {
    const fixture = JSON.parse(
      await readFile(
        resolve(defaultProtocolRoot(), "fixtures/valid/handshake-proof-v1.json"),
        "utf8",
      ),
    ) as {
      runtime: {
        payload: {
          selectedProtocolVersion: string | null;
          authentication: { challenge: string; proof: string };
        };
      };
    };

    expect(validateHandshakeProof(fixture)).toEqual([]);

    const invalidProof = structuredClone(fixture);
    invalidProof.runtime.payload.authentication.proof = "A".repeat(43);
    expect(validateHandshakeProof(invalidProof)[0]?.code).toBe("HANDSHAKE_PROOF_INVALID");

    const uncorrelatedChallenge = structuredClone(fixture);
    uncorrelatedChallenge.runtime.payload.authentication.challenge = "MDEyMzQ1Njc4OWFiY2RlZg";
    expect(validateHandshakeProof(uncorrelatedChallenge)[0]?.code).toBe(
      "HANDSHAKE_CHALLENGE_MISMATCH",
    );

    const unselectedProtocol = structuredClone(fixture);
    unselectedProtocol.runtime.payload.selectedProtocolVersion = null;
    expect(validateHandshakeProof(unselectedProtocol)[0]?.code).toBe(
      "HANDSHAKE_NEGOTIATION_INVALID",
    );
  });

  it("rejects incompatible runtime and client protocol versions", () => {
    const errors = validateProtocolVersion({
      protocolVersion: "2.0",
      payload: { clientProtocolVersion: "0.9" },
    });

    expect(errors).toHaveLength(2);
    expect(errors.every(({ rule }) => rule === "protocol-version-compatible")).toBe(true);
  });

  it("locks the proposal argument JCS and domain-separated hash vector", async () => {
    const fixture = JSON.parse(
      await readFile(
        resolve(defaultProtocolRoot(), "fixtures/valid/proposal-argument-hash-v1.json"),
        "utf8",
      ),
    ) as Record<string, unknown>;

    expect(validateProposalArgumentHash(fixture)).toEqual([]);

    const wrongHash = structuredClone(fixture);
    const proposal = wrongHash["proposal"] as Record<string, unknown>;
    proposal["argumentHash"] = "0".repeat(64);
    expect(validateProposalArgumentHash(wrongHash)[0]?.code).toBe(
      "PROPOSAL_ARGUMENT_HASH_MISMATCH",
    );

    const wrongCanonicalArguments = structuredClone(fixture);
    const contract = wrongCanonicalArguments["hashContract"] as Record<string, unknown>;
    contract["canonicalArguments"] = "{}";
    expect(validateProposalArgumentHash(wrongCanonicalArguments)[0]?.code).toBe(
      "PROPOSAL_ARGUMENT_CANONICAL_MISMATCH",
    );
  });

  it("accepts a continuous transfer with valid lengths and hashes", () => {
    expect(validateBuildPreviewChunks(validTransferFixture())).toEqual([]);
  });

  it.each([
    [
      "non-continuous indexes",
      (fixture: Record<string, unknown>) => {
        const chunks = fixture["chunks"] as Array<Record<string, unknown>>;
        chunks[1]!["chunkIndex"] = 2;
      },
      "CHUNK_SET_INCOMPLETE",
    ],
    [
      "non-canonical base64",
      (fixture: Record<string, unknown>) => {
        const chunks = fixture["chunks"] as Array<Record<string, unknown>>;
        chunks[0]!["data"] = "not-base64";
      },
      "CHUNK_BASE64_INVALID",
    ],
    [
      "incorrect byte counts",
      (fixture: Record<string, unknown>) => {
        const begin = fixture["begin"] as Record<string, unknown>;
        begin["totalCompressedBytes"] = (begin["totalCompressedBytes"] as number) + 1;
      },
      "CONTENT_COMPRESSED_LENGTH_MISMATCH",
    ],
    [
      "incorrect chunk byte counts",
      (fixture: Record<string, unknown>) => {
        const chunks = fixture["chunks"] as Array<Record<string, unknown>>;
        chunks[0]!["chunkBytes"] = (chunks[0]!["chunkBytes"] as number) + 1;
      },
      "CHUNK_LENGTH_MISMATCH",
    ],
    [
      "incorrect chunk hashes",
      (fixture: Record<string, unknown>) => {
        const chunks = fixture["chunks"] as Array<Record<string, unknown>>;
        chunks[0]!["chunkSha256"] = "0".repeat(64);
      },
      "CHUNK_HASH_MISMATCH",
    ],
    [
      "incorrect content hashes",
      (fixture: Record<string, unknown>) => {
        const begin = fixture["begin"] as Record<string, unknown>;
        begin["contentSha256"] = "f".repeat(64);
      },
      "CONTENT_HASH_MISMATCH",
    ],
  ])("rejects %s", (_name, mutate, expectedCode) => {
    const fixture = validTransferFixture();
    mutate(fixture);

    expect(validateBuildPreviewChunks(fixture).map(({ code }) => code)).toContain(expectedCode);
  });

  it("limits gzip expansion to the declared uncompressed byte count", () => {
    const fixture = validTransferFixture();
    const begin = fixture["begin"] as Record<string, unknown>;
    begin["totalUncompressedBytes"] = 1;

    expect(validateBuildPreviewChunks(fixture)[0]?.code).toBe("CONTENT_DECOMPRESSION_FAILED");
  });

  it("reassembles complete chunks by index instead of array order", () => {
    const fixture = validTransferFixture();
    const chunks = fixture["chunks"] as Array<Record<string, unknown>>;
    chunks.reverse();

    expect(validateContractSemantics(fixture, "reassemble-and-verify-sha256")).toEqual([]);
  });

  it("requires recipe slots to match row-major coordinates", () => {
    const recipeView = {
      selectedRecipe: 0,
      recipes: [
        {
          recipeId: "minecraft:test",
          layout: {
            width: 2,
            height: 1,
            ingredients: [{ slot: 1, x: 0, y: 0 }],
          },
        },
      ],
    };

    expect(validateRecipeView(recipeView).map(({ code }) => code)).toContain(
      "RECIPE_SLOT_COORDINATE_MISMATCH",
    );
  });

  it("leaves console source and fixed semicolon literal policy to Paper", () => {
    const capability = validCapabilityFixture();
    const execution = capability["execution"] as Record<string, unknown>;
    execution["source"] = "console";
    execution["template"] = "/say {message} ;literal";

    expect(validateCapabilityManifest(capability)).toEqual([]);
  });

  it("rejects malformed and repeated capability placeholders with stable codes", () => {
    const malformed = validCapabilityFixture();
    (malformed["execution"] as Record<string, unknown>)["template"] = "/say {message";
    expect(validateCapabilityManifest(malformed)[0]?.code).toBe(
      "CAPABILITY_TEMPLATE_PLACEHOLDER_MALFORMED",
    );

    const repeated = validCapabilityFixture();
    (repeated["execution"] as Record<string, unknown>)["template"] = "/say {message} {message}";
    expect(validateCapabilityManifest(repeated)[0]?.code).toBe(
      "CAPABILITY_TEMPLATE_ARGUMENT_DUPLICATE",
    );
  });

  it("uses the documented capability argument and command error codes", () => {
    const cases: Array<{
      readonly expectedCode: string;
      readonly mutate: (capability: Record<string, unknown>) => void;
    }> = [
      {
        expectedCode: "CAPABILITY_REQUIRED_ARGUMENT_UNUSED",
        mutate: (capability) => {
          (capability["execution"] as Record<string, unknown>)["template"] = "/say literal";
        },
      },
      {
        expectedCode: "CAPABILITY_OPTIONAL_ARGUMENT_UNSUPPORTED",
        mutate: (capability) => {
          const argumentsRecord = capability["arguments"] as Record<string, unknown>;
          (argumentsRecord["message"] as Record<string, unknown>)["required"] = false;
        },
      },
      {
        expectedCode: "CAPABILITY_COMMAND_ROOT_MISMATCH",
        mutate: (capability) => {
          (capability["execution"] as Record<string, unknown>)["commandRoot"] = "tell";
        },
      },
      {
        expectedCode: "CAPABILITY_ARGUMENT_RANGE_INVALID",
        mutate: (capability) => {
          const argumentsRecord = capability["arguments"] as Record<string, unknown>;
          (argumentsRecord["message"] as Record<string, unknown>)["minLength"] = 65;
        },
      },
    ];

    for (const { expectedCode, mutate } of cases) {
      const capability = validCapabilityFixture();
      mutate(capability);
      expect(validateCapabilityManifest(capability)[0]?.code).toBe(expectedCode);
    }
  });
});

function validCapabilityFixture(): Record<string, unknown> {
  return {
    id: "example.say",
    version: 1,
    description: "Send one fixed-form message",
    requirements: {
      plugins: [],
    },
    execution: {
      type: "command",
      source: "player",
      commandRoot: "say",
      template: "/say {message}",
    },
    arguments: {
      message: {
        type: "string",
        description: "Message token",
        required: true,
        minLength: 1,
        maxLength: 64,
      },
    },
    effects: {
      category: "READ",
      scope: "request",
      maximumBlocks: null,
    },
    permissions: {
      minimum: "ANY",
    },
    confirmation: {
      required: false,
    },
    reversibility: {
      type: "none",
    },
  };
}
