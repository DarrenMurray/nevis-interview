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
| GCP infra as Terraform: Cloud Run, Artifact Registry, push-triggered deploy, VPC | written, **never applied** |
| `publish-image` workflow: build + push on merge to main, plus manual dispatch | done |
| Persistence, search logic, embeddings | not started |

## Commands

Prefer `make` — it pins the right JDK and fails with a clear message if it cannot find one. `make`
with no target runs the tests.

```sh
make                 # == make test; runs all tests
make build           # package the jar, with tests
make run             # start the API on :8080
make docker-build    # build the image (nevis/search-api:dev)
make docker-smoke    # compose up, assert GET /search returns 200 and schema migrated
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

## Deployment

Terraform in `terraform/` (see `terraform/README.md`). **Never applied — nothing is
running and no GCP cost has been incurred.** `terraform validate` passes against
`hashicorp/google ~> 8.2`; that is schema validation, not a plan against a real project.

```
merge to main / manual dispatch -> publish-image.yml -> Artifact Registry
  -> Pub/Sub "gcr" -> Cloud Build trigger -> gcloud run deploy -> Cloud Run
```

Facts that shaped this, worth not rediscovering:

- **Nothing in GCP watches a registry.** Cloud Run resolves an image to a digest at deploy
  time and keeps serving it, so moving `:latest` changes nothing by itself. The Cloud Build
  Pub/Sub trigger is what makes push-to-update real.
- **Artifact Registry publishes to a topic named exactly `gcr`, and does not create it.**
  Terraform creates it. Payload fields are `action` (INSERT/DELETE), `digest`, `tag`, where
  `tag` is the full image reference. The trigger filters on
  `_ACTION == "INSERT"` and the tag matching `:latest`; without a filter every push, tag
  and delete in the project would deploy.
- **Cloud Run over Compute Engine** despite the brief saying "GCP Compute": a VM or MIG
  cannot redeploy itself on push, and bills continuously.
- **`ignore_changes = [template[0].containers[0].image]`** on the Cloud Run service is
  load-bearing. The deployer owns the running image after the first apply; without this
  every `terraform apply` would roll the service back to whatever `:latest` resolved to at
  plan time.
- **Three separate service accounts** (publisher / deployer / runtime). CI can push images
  but deliberately cannot deploy.
- **Providers are not vendored.** `plugin_cache_dir` in `~/.terraformrc` points at
  `~/.terraform.d/plugin-cache`; `.terraform/` holds symlinks (40K, versus 145M copied per
  project). `.terraform/` is gitignored, `.terraform.lock.hcl` is committed.
- **Cloud Run probe paths and query strings**: whether `http_get.path` accepts `?q=...` is
  undocumented, and `/search` without `q` returns 400, so the startup probe is
  `tcp_socket` for now. Switch to `http_get` on `/actuator/health` when actuator is added.
- **Terraform variables come from `.env`, not `terraform.tfvars`.** `.env` uses
  Terraform's native names (`TF_VAR_project_id`, `TF_VAR_region`) so there is no mapping
  layer: the Makefile re-exports them, `set -a; . ./.env; set +a` makes bare `terraform`
  work, and CI sets the same names from secrets. Exported only when non-empty (an exported
  empty `TF_VAR_region` would override the default with `""`). **Do not add a
  `terraform.tfvars`** — it takes precedence over `TF_VAR_*` and would silently beat
  `.env`. `make tf-config` prints what Terraform will actually use.
- **Bare `terraform plan` prompting for `project_id` means the shell lacks the vars**, not
  that the config is broken — source `.env` or use `make tf-plan`.
- **A killed plan/apply can leave the GCS state lock held.** Symptom is
  `Error acquiring the state lock ... 412 conditionNotMet` naming
  `search-api/default.tflock`. Fix: `terraform force-unlock <ID>`, or delete that object
  once certain no run is live. Never run long Terraform commands detached.
- **ADC is separate from the gcloud CLI credential.** `gcloud auth login` does not create
  Application Default Credentials; the Google provider needs
  `gcloud auth application-default login`, or `terraform init` fails with
  `could not find default credentials`. Switching `gcloud config set account` does not
  change what Terraform uses.
- **State is remote, in GCS** (`backend "gcs"` in `terraform/versions.tf`), so local and CI
  runs share one state object. GCS locks natively via object generations — no lock table.
  The bucket name is a committed literal because backends cannot take variables and a
  per-machine `backend.hcl` would let the two diverge silently.
- **The state bucket is created by `scripts/bootstrap-tfstate.sh`** (`make tf-bootstrap
  GCP_PROJECT=...`), not by Terraform — `init` needs the backend to exist first, so the
  config that uses the bucket cannot create it. It ships with the placeholder
  `REPLACE_ME-tfstate`, so `tf-init` fails until the real name is set.
- **Use `make tf-validate`, not `terraform validate`, before the bucket exists.** It runs
  `init -backend=false` first, so it needs neither the bucket nor credentials.
- **`terraform` is not installed system-wide** — it is at `~/.local/bin/terraform` (1.16.2).
  The Makefile's `check-tf` guard reports this rather than failing cryptically.
- **No workflow runs Terraform yet.** Shared state is in place, but a CI Terraform identity
  (objectAdmin on the state bucket plus near-editor on the project) is deliberately not
  created — `github-publisher` cannot touch state.

## GitHub Actions

Three workflows:

- `test.yml` — tests + docker build + image smoke test, on push/PR.
- `publish-image.yml` — builds and pushes to Artifact Registry on merge to `main` and on
  manual dispatch. Tags `:latest` (what the deployer watches) and `:sha-<short>` (so a
  rollback target can be named).
- `deploy.yml` — runs Terraform against the shared GCS state: `plan` on PRs touching
  `terraform/**`, `apply` on merge to `main`, plan-or-apply on dispatch. Applies the
  **saved plan file**, so what was reviewed is what runs. Concurrency group
  `terraform-state` with cancellation disabled — cancelling mid-apply would leave the
  state lock held.

All auth is Workload Identity Federation. `id-token: write` is required in every job that
authenticates, or the token cannot be minted.

**The first `terraform apply` must be local.** `deploy.yml` authenticates as the
`terraform-ci` service account, which the config itself creates — it cannot exist before
Terraform has run once.

### Secrets

Five repository secrets, shared by both GCP workflows (previously repository `vars`; moved
to secrets so there is one place to look):

| Secret | Used by |
|---|---|
| `GCP_PROJECT_ID` | both — exported as `TF_VAR_project_id` |
| `GCP_REGION` | both — exported as `TF_VAR_region` |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | both |
| `GCP_SERVICE_ACCOUNT` | `publish-image` (the publisher identity) |
| `GCP_TERRAFORM_SERVICE_ACCOUNT` | `deploy` (the terraform-ci identity) |

`deploy.yml` checks all four of its own secrets are non-empty before doing anything: an
unset secret is an empty string, and an empty `project_id` fails deep inside an apply
rather than obviously up front.

**Do not branch on workflow inputs in a GitHub expression.** On events that are not
`workflow_dispatch` those inputs are empty strings, and `==` coerces both operands to
numbers, so `'' == false` is **true**. This already caused one bug: `publish-image` would
have skipped `:latest` on every merge to main and never deployed. Both `publish-image`
(tag choice) and `deploy` (plan vs apply) now decide in shell, gated on
`github.event_name`.

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
