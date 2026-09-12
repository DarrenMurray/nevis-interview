# CLAUDE.md

## Project

Take-home assignment for Nevis: a **Search API across clients and their documents** for a
WealthTech advisor platform. Two search behaviours define the assignment:

1. **Clients — lexical.** `?q=NevisWealth` must return the client with email
   `john.doe@neviswealth.com` (case-insensitive substring over email/name/description).
2. **Documents — semantic.** `?q=address proof` must return a document containing `"utility bill"`.
   These share no characters, so keyword matching cannot do it; this needs embeddings.

**Hard constraint: no external LLM/embedding API.** No Anthropic, OpenAI or Voyage; no API keys.
Embeddings are to be computed in-process from a bundled ONNX model. The brief's "use LLMs" line
means use them to *write* the solution, not to call one at runtime.

## Current state

Endpoints exist and are **stubbed**: they validate input and return correctly-shaped responses, but
hold no state and implement no search. `GET /search` always returns `[]`.

| Area | State |
|---|---|
| `POST /clients`, `POST /clients/{id}/documents`, `GET /search` | stubbed, validated |
| OpenAPI 3.1 + Swagger UI (annotation-driven, springdoc) | done |
| Dockerfile (multi-stage, layered, non-root) | done, **never actually built** — see Toolchain |
| Makefile, GitHub Actions (`test` workflow) | done |
| Tests | smoke test + 6 OpenAPI contract tests |
| Persistence, search logic, embeddings, docker-compose, seed data, Terraform | not started |

## Commands

Prefer `make` — it pins the right JDK and fails with a clear message if it cannot find one. `make`
with no target runs the tests.

```sh
make                 # == make test; runs all tests
make build           # package the jar, with tests
make run             # start the API on :8080
make docker-build    # build the image (nevis/search-api:dev)
make docker-smoke    # boot the built image, assert GET /search returns 200
make ci              # what CI runs: test + docker-build + docker-smoke
make help            # list targets and resolved settings
```

Raw Maven works too, but `JAVA_HOME` must be set explicitly:

```sh
JAVA_HOME=~/.jdks/current ./mvnw test
```

Override the JDK with `make JDK=/path/to/jdk-25 ...`. The variable is `JDK`, **not** `JAVA_HOME` —
an inherited `JAVA_HOME` would silently win over `?=` and break the build.

## API documentation

Annotation-driven via springdoc; there is no hand-maintained spec file to keep in sync.

- `GET /v3/api-docs` — OpenAPI **3.1** JSON (`.yaml` also served)
- `GET /swagger-ui.html` — Swagger UI

Metadata (title, description, tags, servers) lives on `config/OpenApiConfig`. Per-endpoint docs are
`@Operation` / `@ApiResponses` on the controllers; field docs and examples are `@Schema` on the DTO
records. Schema names are set via `@Schema(name = ...)` so the document reads `Client` / `Document`
rather than `ClientResponse` / `DocumentResponse`.

## Toolchain

Java 25 (Temurin 25.0.4.1) at `~/.jdks/jdk-25.0.4.1+1`, symlinked `~/.jdks/current`.
**Not on `PATH`** — `java` still resolves to JDK 17, so `JAVA_HOME` must be set for any direct
Maven invocation. (`~/.jdks/ms-25.0.4.1`, a Microsoft build, predates this and is unused.)

`mvn` is not installed — always use the committed wrapper `./mvnw`.

**Docker cannot be run from this session.** The daemon socket is root-owned with no `docker` group,
so Docker needs `sudo`, and sudo requires a password. The Makefile detects this and falls back to
`sudo docker` (visible in `make help`). Consequence: the Dockerfile has **never been built** —
every step was instead validated on the host (`dependency:go-offline`, `package`, `jarmode=tools
extract --layers`, then booting the flattened layer layout, which served HTTP 200). CI is what will
first prove the container itself. To verify locally, the user must run `make ci` in their own shell.

`gcloud` is installed and authed (project `product-pipe`). `terraform` is not installed.

## Version gotchas

These cost real debugging time — do not "fix" them back:

- **Spring Boot 4.1.1**, not `4.1.1.RELEASE`. Spring Initializr reports the version id with a
  `.RELEASE` suffix, but no such artifact exists in Maven Central; the parent POM fails to resolve.
- **Starters are renamed in Boot 4**: `spring-boot-starter-webmvc` (not `-web`), and the test
  starters are `spring-boot-starter-webmvc-test` / `spring-boot-starter-validation-test`.
- **springdoc 3.x is required** (currently 3.1.1). The 2.x line targets Boot 3 / Jackson 2.
- **Boot 4 ships Jackson 3** (`tools.jackson.*`). `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS`
  no longer exists — setting `spring.jackson.serialization.write-dates-as-timestamps` fails context
  startup with `No enum constant`. Unnecessary anyway: Jackson 3 writes ISO-8601 by default.
- **Two Jacksons are on the classpath.** Boot 4 serialises with Jackson 3 (`jackson-databind:3.x`,
  package `tools.jackson`), while swagger-core bundles Jackson 2 (`jackson-databind:2.x`, package
  `com.fasterxml.jackson.databind`). Annotations are shared (`jackson-annotations` keeps the
  `com.fasterxml.jackson.annotation` package), which is why `@JsonInclude` works on the DTOs.
  **This split silently broke the docs**: swagger-core could not see
  `spring.jackson.property-naming-strategy`, so the document advertised `firstName` while the API
  served `first_name`. Fixed by the `ModelResolver` bean in `config/OpenApiConfig`, which hands
  swagger-core a Jackson 2 mapper configured `SNAKE_CASE`. That bean must stay in step with
  `application.yaml`; `OpenApiContractTest` fails if they diverge (verified — removing the bean
  fails 4 tests).
- **Do not put `@Validated` on a controller class** to validate `@RequestParam`. It routes through
  the legacy `ConstraintViolationException` path and yields **500**; Spring 7's built-in method
  validation already handles bare `@NotBlank` on a param and returns **400**.
- **`LocalServerPort` moved** to `org.springframework.boot.test.web.server` in Boot 4 (not
  `org.springframework.boot.web.server.test`).

## Conventions

- Packages: `controllers/` (HTTP layer — not `web/`), `dto/` (request/response records),
  `config/` (Spring configuration).
- DTOs are **records**; validation and `@Schema` annotations live on them.
- JSON is **snake_case** via `spring.jackson.property-naming-strategy`; Java fields stay camelCase.
  Do not hand-write `@JsonProperty` for this.
- `POST` returns 201 + `Location`. `GET /search` returns `200` with an array — **empty array, not
  404**, when nothing matches.
- `/search` items are a tagged union (`type: "client" | "document"`, plus `score`), since the spec
  leaves items as a bare object and invites extending responses.
- Documented behaviour must match served behaviour. `OpenApiContractTest` compares the OpenAPI
  schemas against real response bodies — populate every optional field when adding cases there, or
  `default-property-inclusion: non_null` drops nulls and the comparison silently passes.
- `main` is the default branch and the base for PRs. Nothing is committed yet.
