import { describe, expect, it } from "vitest";

import { bootstrap } from "../src/bootstrap/index.js";
import { runtimeIdentity } from "../src/version.js";

describe("runtime bootstrap", () => {
  it("initializes Fastify without opening a network listener", async () => {
    const result = await bootstrap();

    try {
      expect(result.identity).toEqual(runtimeIdentity);
      expect(result.app.server.listening).toBe(false);
      expect(result.app.addresses()).toEqual([]);
    } finally {
      await result.app.close();
    }
  });
});
