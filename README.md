# onecx-ai-provider-runtime
OneCX AI Provider Runtime Service

## OpenAI model request settings

The runtime reads the existing per-model `modelConfig` JSON string from the resolved snapshot.
Only OpenAI applies these settings; Anthropic and Ollama remain unchanged.

```json
{"api":"responses","reasoningEffort":"medium"}
```

Only `api` and `reasoningEffort` are supported:

- `api`: `chat-completions` or `responses`. Missing configuration, `{}`, or an absent `api` uses Chat Completions.
- `reasoningEffort`: optional free-text string, trimmed before use. Missing or blank effort is omitted.
  Explicit values such as `none` are forwarded; no model-name-based defaults are inferred.
- Chat Completions sends `reasoning_effort`; Responses sends `reasoning.effort`.
  Neither transport adds temperature, top-p, or other generation defaults.

The runtime enforces the existing 255-character storage limit before constructing an OpenAI model.
Oversized snapshots return HTTP 400 with `RUNTIME_MODEL_CONFIG_INVALID`.
Malformed existing JSON, duplicate/unknown keys, unsupported shapes, and unsupported API values are legacy:
the runtime warns without logging the configuration contents and treats the entire configuration as unset.
The management service must reject invalid new or explicitly changed configuration before persistence.

Both transports implement `ChatModel` and preserve MCP, delegate-agent, and skill tool workflows.
Responses uses `store: false` and includes `reasoning.encrypted_content` to replay encrypted reasoning
alongside tool calls and outputs in subsequent turns. Text-tool normalization also preserves this state.
Streaming is not exposed by the runtime chat API.

Unsupported Responses features return HTTP 400 with `RUNTIME_MODEL_FEATURE_UNSUPPORTED`, including
assistant-generated images and SDK-unsupported content/parameters (top-k, frequency/presence penalties,
and stop sequences), rather than silently discarding them.
Invalid tool continuations or other locally invalid OpenAI requests return HTTP 400 with
`RUNTIME_MODEL_REQUEST_INVALID` before HTTP, without retries.

Chat Completions retains the configured retry count and SDK backoff policy. Endpoint validation/unsupported
responses (HTTP 400, 404, 405, 415, 422, or 501) are never retried. Responses makes no retries.
Both transports use the configured provider timeout and request/response logging.
There is no API fallback or reasoning downgrade.

Endpoint rejection returns HTTP 502 with `RUNTIME_MODEL_ENDPOINT_REJECTED`; other OpenAI provider failures
return HTTP 502 with `RUNTIME_MODEL_PROVIDER_FAILED`. Structured errors contain safe details, not raw provider
errors or credentials, and survive agent, delegate-tool, and asynchronous invocation wrappers.
Request/response logging remains an explicit operational setting; enable it only under appropriate data-handling controls.
