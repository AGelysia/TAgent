import type { ModelProviderHealthCheck } from "../health/model-provider.js";

export const modelGenerationFailureCodes = [
  "MODEL_AUTHENTICATION_FAILED",
  "MODEL_UNAVAILABLE",
  "MODEL_RATE_LIMITED",
  "MODEL_RESPONSE_INVALID",
  "PROVIDER_UNAVAILABLE",
] as const;

export type ModelGenerationFailureCode = (typeof modelGenerationFailureCodes)[number];

export interface ModelGenerationRequest {
  readonly provider: "openai";
  readonly model: string;
  readonly apiKey: string;
  readonly input: string;
  readonly maxOutputTokens: number;
  readonly signal: AbortSignal;
}

export interface ModelGenerationUsage {
  readonly inputTokens: number;
  readonly outputTokens: number;
}

export interface ModelGenerationResult {
  readonly fallbackText: string;
  readonly usage?: ModelGenerationUsage;
}

export interface ModelProvider extends ModelProviderHealthCheck {
  generate(request: ModelGenerationRequest): Promise<ModelGenerationResult>;
}

export class ModelGenerationError extends Error {
  public readonly code: ModelGenerationFailureCode;

  public constructor(code: ModelGenerationFailureCode) {
    super(code);
    this.name = "ModelGenerationError";
    this.code = code;
  }
}

export class UnsupportedModelProvider implements ModelProvider {
  public async check(): Promise<{ readonly ok: false; readonly code: "PROVIDER_UNSUPPORTED" }> {
    return Promise.resolve({ ok: false, code: "PROVIDER_UNSUPPORTED" });
  }

  public async generate(): Promise<ModelGenerationResult> {
    throw new ModelGenerationError("PROVIDER_UNAVAILABLE");
  }
}
