# repcheck-llm-adapter

Vendor-neutral LLM provider layer + the bounded agentic tool-use loop (bill-decomposition plan F2). `LlmProvider.open`
yields a stateful `LlmSession` (the session seam — history + tools retained, callers send deltas only);
`DefaultAgenticLlmRunner` drives the loop to a schema-valid `submit`. Providers: `OllamaLlmProvider` (`/api/chat`
tool calling); `ClaudeLlmProvider` planned (F2c).

Part of the [RepCheck](https://github.com/Eligio-Taveras) platform -- a citizen-facing system that helps users understand how their legislators vote relative to their personal political interests.

## Dependencies

This module depends on the following shared libraries:

- **[shared-models](https://github.com/Eligio-Taveras/repcheck-shared-models)** -- Domain types (DTOs, DOs, enums, type classes)
- **[pipeline-models](https://github.com/Eligio-Taveras/repcheck-pipeline-models)** -- Pipeline operational types (events, retry, error classification, workflow)

## Tech Stack

| Concern | Technology |
|---------|-----------|
| Language | Scala 3.7.3 |
| Effect system | Cats Effect (tagless final `F[_]`) |
| HTTP | http4s Ember |
| JSON | Circe (semi-auto derivation) |
| Streaming | FS2 |
| Config | PureConfig (auto-derivation) |
| Testing | ScalaTest + MockitoScala + WireMock |
| Build | SBT 1.9.9 |
| Linting | WartRemover, Scalafix, tpolecat |
| Container | Google Distroless Java 21 |

## Prerequisites

- JDK 21 (Temurin recommended)
- SBT 1.9.9

## Build Commands

```bash
sbt compile              # Compile with WartRemover + tpolecat
sbt test                 # Run all tests
sbt scalafmtCheckAll     # Check formatting (fails if unformatted)
sbt scalafmtAll          # Auto-format all source files
sbt scalafixAll --check  # Check import ordering and lint rules
sbt scalafixAll          # Auto-fix import ordering
sbt coverage test coverageReport  # Run tests with coverage
```

## Conformance tests against live Ollama (DockerRequired)

`OllamaLlmProviderConformanceSpec` runs the agentic loop against a REAL Ollama and is excluded from `sbt test`
(the build sets `-l DockerRequired`). To run it, Ollama must be reachable (default `http://localhost:11434`) with a
tool-calling model pulled (default `qwen3:0.6b`):

```bash
ollama pull qwen3:0.6b   # once
```

Then (the `-l` exclusion must be replaced with `-n`, so use a command file piped to sbt):

```text
project repcheckllmadapter
set Test / testOptions := Seq(Tests.Argument(TestFrameworks.ScalaTest, "-n", "DockerRequired"))
testOnly com.repcheck.llm.adapter.ollama.OllamaLlmProviderConformanceSpec
```

Override the target with `OLLAMA_BASE_URI` / `OLLAMA_TOOL_MODEL` env vars.

## Project Structure

```
repcheck-llm-adapter/
  src/
    main/scala/          # Application code
    test/scala/          # Tests
doc-generator/           # Doc compression utility
docs/                    # Full documentation
.claude/agent-docs/      # Compressed docs for agents
```

## CI Checks

Before pushing, always run the full CI check suite:

```bash
source scripts/ci-functions.sh
CreatePR "title" "body"   # For new PRs: runs checks, pushes, creates PR
pushToPR                   # For existing PRs: runs checks, pushes
```

## Publishing

Published to [GitHub Packages](https://github.com/Eligio-Taveras/repcheck-llm-adapter/packages) via sbt-dynver (git-based semver). Add as a dependency:

```scala
libraryDependencies += "com.repcheck" %% "repcheck-llm-adapter" % "<version>"
```

## Documentation

See `CLAUDE.md` for the agent routing guide, coding conventions, and task routing table.
