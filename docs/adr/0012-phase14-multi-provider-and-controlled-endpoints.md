# ADR 0012: Phase 14 Multi-Provider and Controlled Endpoints

## Status

Accepted for the Phase 14 implementation. Live-provider credential acceptance
remains a pre-publication gate.

## Context

The Phase 13 candidate had one production OpenAI Responses adapter. Server
operators also need Anthropic Claude, DeepSeek, Gemini, and reviewed gateways,
but a provider name is not enough to make their request, authentication, health,
tool-call, continuation, and usage contracts interchangeable. Treating every
"OpenAI-compatible" service as equivalent would turn provider responses and
redirects into an unbounded protocol surface.

Operators may also need a regional proxy or local inference gateway. A custom
endpoint receives the configured API key and the complete model request, so it
is a credential and data trust decision rather than a cosmetic routing option.
Pricing cannot be derived safely from either an arbitrary model name or an
arbitrary gateway.

## Decision

- Runtime keeps the strict `configVersion: 2` format. The `model.provider` enum
  expands to `openai`, `anthropic`, `deepseek`, `gemini`, and
  `openai-compatible`; existing OpenAI v2 configuration without `baseUrl`
  remains valid.
- Each provider selects one fixed protocol adapter:

  | Provider            | Protocol                    | Default base URL                                   |
  | ------------------- | --------------------------- | -------------------------------------------------- |
  | `openai`            | Responses                   | `https://api.openai.com/v1`                        |
  | `anthropic`         | Messages                    | `https://api.anthropic.com/v1`                     |
  | `deepseek`          | Chat Completions            | `https://api.deepseek.com`                         |
  | `gemini`            | stateless `generateContent` | `https://generativelanguage.googleapis.com/v1beta` |
  | `openai-compatible` | Chat Completions            | none; `baseUrl` is required                        |

- Every official profile may override `model.baseUrl`. The override must expose
  that profile's exact health and generation contract; changing the URL does not
  change adapters. `openai-compatible` requires `GET models` plus
  `POST chat/completions` behavior and serial function calling. No claim is made
  that an arbitrary compatible endpoint or model will work.
- A base URL is bounded and canonicalized. It must be HTTPS, except that plain
  HTTP is allowed only for literal `127.0.0.1` or `[::1]`. User information,
  query strings, fragments, control characters, and redirects are rejected.
  Path suffixes are retained and provider endpoint paths are appended to them.
- Runtime emits the safe warning code `MODEL_CUSTOM_BASE_URL` with only the
  known field `/model/baseUrl` whenever an explicit base URL is configured. It
  does not log the URL. The configured API key is nevertheless sent to that
  endpoint using the selected adapter's authentication header, so operators
  must review and control the destination before startup.
- Provider selection is static for one Runtime configuration. Runtime does not
  retry across providers, rotate keys or models, or silently fall back to a
  different adapter. A health or generation failure remains explicit.
- All adapters retain the bounded non-streaming, strict-JSON, serial-tool loop.
  The selected model must implement the adapter's tool/function-calling
  contract; text generation alone is insufficient for modules that expose
  tools.
- OpenAI retains Responses continuations and requests provider storage off.
  Anthropic uses Messages content blocks. DeepSeek explicitly sends
  `thinking: { type: disabled }` so Chat Completions tool continuation never
  depends on replaying plaintext chain-of-thought. Gemini uses stateless
  `generateContent`: Runtime reconstructs the bounded content and function
  response sequence for each round rather than relying on a provider session.
  Gemini tool declarations use the official `parametersJsonSchema` field so
  closed nullable types and numeric enums retain their schema semantics.
- Runtime never downloads price data. Operators configure input/output
  micro-USD rates and the conservative per-round reservation for the selected
  provider, model, account, and gateway. DeepSeek reports one aggregate
  `prompt_tokens` count while cache-hit and cache-miss input may have different
  prices; its configured input rate must therefore use the higher cache-miss
  rate, never the hit or an average rate. Gemini accounting adds
  `toolUsePromptTokenCount` to prompt input and `thoughtsTokenCount` to candidate
  output. The local monthly control remains an admission bound, not a provider
  billing cap.
- An HTTP failure from any explicit custom endpoint is classified as
  `BILLABILITY_UNKNOWN`. Runtime settles the active round at its configured
  reservation instead of assuming the gateway did not bill it. Official
  endpoint failures may be released only where the native adapter can classify
  them `NOT_BILLABLE`.
- Automated tests use injected HTTP implementations and synthetic credentials
  to cover protocol shape, health mapping, bounded parsing, tool continuation,
  endpoint validation, redirect rejection, and provider factory selection.
  Real keys are never committed or added to test output.

## Consequences

Operators can select a native supported adapter or a deliberately reviewed
Chat Completions gateway without weakening Paper authority, Runtime request
limits, or the existing cost ledger. Provider-specific continuation data stays
inside Runtime and never becomes Runtime-Paper protocol authority.

An explicit endpoint increases operator responsibility. HTTPS validation and
redirect refusal protect routing mechanics but do not establish that the
destination is trustworthy, private, protocol-correct, or compatible with a
particular model. The endpoint can receive prompts, tool definitions/results,
and the API key, and its own storage, billing, regional, and privacy policy still
applies.

Before publication, maintainers must run sanitized live-key smoke tests for each
official profile they intend to claim: readiness, private text, at least one
serial tool call and continuation, usage accounting, cancellation/timeout, and
safe failure output. A reviewed local or HTTPS `openai-compatible` fixture must
also prove the documented contract. Missing live credentials are recorded as a
test gap, not converted into a pass.

Phase 14 changes the Runtime payload and therefore invalidates the candidate
binding of the earlier graphical acceptance. After a clean candidate produces
new payload and archive fingerprints, maintainers must repeat the complete
Phase 13 physical-client graphical checklist against those exact hashes. The
historical acceptance for commit `3735c5e` cannot authorize a Phase 14 tag or
Release.
