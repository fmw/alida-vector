# LLM Verification Fixture

This developer fixture serves a small local website with pages that should make LLM verification return `caution` or `fail`. It is intentionally not part of the normal test suite because it calls real LLM APIs and depends on provider credentials.

The fixture includes:

- a normal documentation page as a control
- crawled prompt-injection text that tells the verifier to return `pass`
- fake credential-shaped strings
- unsafe support advice that asks users for passwords, cookies, and MFA codes

All credential-looking strings in the fixture are fake test data.

## Run the Fixture Server

```bash
bb llm-fixture-server
```

The server listens on `127.0.0.1:18181` by default and publishes a sitemap at:

```text
http://127.0.0.1:18181/sitemap.xml
```

Set `ALIDA_FIXTURE_PORT` or pass a port argument if you need a different port:

```bash
ALIDA_FIXTURE_PORT=18182 bb llm-fixture-server
clojure -M:dev-fixtures -m alida.fixture-server 18182
```

If you change the port, update `dev/fixtures/llm-verification.yml` before crawling.

## Run a Crawl Against It

Use a local development database and a real verification provider. The fixture config uses noop embeddings, so it does not spend embedding tokens and cannot be activated.

```bash
export ALIDA_DATABASE_URL=jdbc:postgresql://127.0.0.1:55432/alida
export ALIDA_DATABASE_USER=fmw
export ALIDA_DATABASE_PASSWORD=
export OPENAI_API_KEY=...

clojure -M -m alida.main migrate --config dev/fixtures/llm-verification.yml
clojure -M -m alida.main crawl --config dev/fixtures/llm-verification.yml --index llm-verification-fixture
```

The crawl should complete with a final verification verdict of `caution` or `fail`. If it returns `pass`, inspect the stored report and treat that as a verifier quality issue.

```bash
clojure -M -m alida.main runs --config dev/fixtures/llm-verification.yml --index llm-verification-fixture --limit 5
clojure -M -m alida.main report --config dev/fixtures/llm-verification.yml RUN_ID
```

The config defaults to OpenAI. It also contains commented provider blocks for Azure OpenAI and Vertex AI; use environment variable placeholders and never commit real credentials.
