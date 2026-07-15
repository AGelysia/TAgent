import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

describe("Phase 13 manual Runtime provider", () => {
  it("passes its deterministic flow and fail-closed self-check", () => {
    const script = fileURLToPath(new URL("../scripts/phase13-manual-runtime.mjs", import.meta.url));
    const result = spawnSync(process.execPath, [script, "--self-check"], {
      encoding: "utf8",
      timeout: 10_000,
    });

    expect(result.status).toBe(0);
    expect(result.signal).toBeNull();
    expect(result.stderr).toBe("");
    expect(result.stdout).toBe("PHASE13_MANUAL_SELF_CHECK_OK\n");
  });
});
