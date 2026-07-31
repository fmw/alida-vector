# Verification attestations

Alida assigns every LLM verification input a canonical SHA-256 hash. The hash
covers the exact provider-facing prompts, the system prompt, provider and model,
generation parameters, prompt policy version, deterministic gate version, and
verification-input format version. Run IDs are deliberately excluded.

The strict hash makes repeated verification deterministic at the decision
boundary: Alida reuses a result only when the verifier would otherwise receive
the same semantic input. A content, diff, prompt, model, sampling, policy, gate,
or batching change produces a different hash and a fresh provider call.

## Local caching

Attestations are enabled by default. After a provider call, Alida stores its
structured result in `alida_verification_attestations`. A later crawl using the
same hash and attestor reuses that result instead of calling the provider.

Give each independently operated environment a stable attestor name:

```yaml
verification:
  provider: openai
  model: gpt-4.1-mini
  api_key: ${OPENAI_API_KEY}
  prompt_policy_version: "2026-06-08"
  deterministic_gate_version: "2026-06-08"
  attestations:
    attestor: pre-production
```

If `attestor` is omitted, it defaults to `local`. Set `attestations.enabled` to
`false` only when every verification must make a provider call.

## Reusing a trusted pre-production result

A candidate environment can consult one or more migrated Alida metadata
databases before its local cache. Configure read-only database credentials and
an explicit allowlist of attestor names:

```yaml
verification:
  provider: openai
  model: gpt-4.1-mini
  api_key: ${OPENAI_API_KEY}
  prompt_policy_version: "2026-06-08"
  deterministic_gate_version: "2026-06-08"
  attestations:
    attestor: candidate
    trusted_sources:
      - name: pre-production
        type: postgres
        jdbc_url: ${ALIDA_TRUSTED_DATABASE_URL}
        user: ${ALIDA_TRUSTED_DATABASE_USER}
        password: ${ALIDA_TRUSTED_DATABASE_PASSWORD}
        attestors: [pre-production]
```

Trusted sources are checked in configuration order, before the local cache.
Within a source, `attestors` is also an ordered allowlist. On an exact match,
the candidate records `llm_result_source` as `trusted:<source-name>` and keeps
the original attestor as provenance. No documents, chunks, embeddings, or run
lifecycle state are copied between databases.

A configured trusted database is part of the verification trust boundary. A
connection or query failure fails the crawl rather than silently making a new
provider decision. This makes environment divergence visible. Use a small
read-only connection pool; `max_pool_size` defaults to `1` for trusted sources.

## Deployment

Run `alida-vector migrate` against every participating metadata database before
enabling attestations. The migration adds verification provenance to
`alida_verifications` and creates `alida_verification_attestations`.

Grant the candidate identity only `CONNECT`, schema `USAGE`, and `SELECT` on
the trusted attestation table. It does not need write access to the trusted
database. Network policy should allow the candidate job to reach only the
configured database endpoint.

Treat `prompt_policy_version` and `deterministic_gate_version` as cache
invalidation controls: bump them whenever a semantic change is not already
represented in the generated prompts or versioned verifier implementation.

Attestation reuse intentionally does not force two crawls to agree when their
diffs differ. For example, environments with different live baselines produce
different verifier prompts and therefore different hashes, even when their
current document counts match.
