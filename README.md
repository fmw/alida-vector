# Alida Vector

Alida Vector is a tool for building and automatically refreshing vector-backed knowledge bases. It crawls configured sources, extracts content and metadata, chunks content for embedding, and stores the result in a vector database. Before promoting a candidate index to a live index, it validates the changes using an LLM to catch quality issues.

The main use case is retrieval-augmented generation (RAG): keeping a chatbot or assistant supplied with current, searchable context from external sources.

## Project status

Alida Vector is in early development. I built the first version for my own use and am using it in production, but the public project is still taking shape. The goal is to make it a reusable open-source tool.

The current storage backend is pgvector. Embeddings currently use OpenAI, but the intention is to support additional embedding providers later.

The project is written in Clojure.

## How it works

1. Discover documents from configured external sources.
2. Fetch pages or files.
3. Extract content and metadata.
4. Clean and normalize content.
5. Split large documents into embedding-sized chunks.
6. Create embeddings.
7. Store documents and vectors in the vector database.
8. Compare the new index with the previous index.
9. Use an LLM as a sanity check on the differences between indexes.
10. Promote the validated result to the live index.

## Data sources

- crawlable websites, using sitemaps and regular HTML parsing
- JavaScript-heavy pages, especially Jira Service Management support sites, using a headless browser
- files and structured content exports

## Automate, but verify

Scraping data from external sources often introduces subtle problems: pages disappear, irrelevant text leaks into content, JavaScript rendering breaks, and sites change structure.

Alida Vector uses an LLM verification step to validate scraped data before a candidate index is promoted to the live index. The goal is to automate routine refreshes while catching obvious crawl, extraction, or indexing problems before users are exposed to bad data.

## Running it

Alida Vector is designed to run as a scheduled job, such as a Docker container running on Kubernetes.

For crawl cleanup and extraction tuning, see [Crawl Tuning](docs/tuning.md).

For development-time verifier checks with intentionally unsafe local content, see [LLM Verification Fixture](docs/llm-verification-fixture.md).

## Contact

If you're using Alida Vector or have any questions, I'd love to hear from you. You can make a GitHub issue or reach out through fmw@vix.io.

## License

MIT

## History

Alida Vector is a continuation of [Alida](https://github.com/fmw/alida/), a Clojure and Apache Lucene project I built for a presentation at EuroClojure 2012 in London.

The project is named after [my grandmother](https://www.youtube.com/watch?v=UyGlPaDqgpI), who has always been a great archivist.
