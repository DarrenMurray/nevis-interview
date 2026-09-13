# CLAUDE.md

## Project

Search API over clients and their documents.

- Clients match lexically: `?q=NevisWealth` returns the client whose email is
  `john.doe@neviswealth.com`.
- Documents match semantically: `?q=address proof` returns a document containing "utility bill".
- Document content is summarised extractively.

Embeddings are computed in-process from an ONNX model bundled in the image. There is no external
AI service and no API key.

## Commands

`make` selects the correct JDK and fails with an actionable message if one is not found.

```sh
make                 # all tests
make run             # start the API on :8080, requires a reachable Postgres
make docker-smoke    # compose stack end-to-end, asserts the three core use cases
make ci              # tests, image build, end-to-end
make tf-plan         # terraform plan
make help            # targets and resolved settings
docker compose up --build
```

Raw Maven requires `JAVA_HOME=~/.jdks/current ./mvnw`. Override with `make JDK=/path/to/jdk-25`;
the variable is `JDK`, not `JAVA_HOME`, because an inherited `JAVA_HOME` would take precedence
over `?=`.

## Configuration

`.env` holds Terraform's native `TF_VAR_*` names, so one file serves `make`, a shell running
`set -a; . ./.env; set +a`, and CI. The Makefile exports every `TF_VAR_*` it finds; naming them
individually drops any variable added later.

| Key | Purpose |
|---|---|
| `TF_VAR_project_id` | GCP project, and the default for `make tf-bootstrap` |
| `TF_VAR_region` | region and state bucket location |
| `TF_VAR_alert_email` | optional; empty disables the alerting resources |

## Toolchain

Java 25 (Temurin 25.0.4.1) at `~/.jdks/current`, not on `PATH`. `mvn` is not installed; use
`./mvnw`. Terraform 1.16.2 at `~/.local/bin/terraform`. `gcloud` is authenticated against
`interview-prep-505511`.

Docker requires membership of the `docker` group, which the daemon expects but which did not
exist initially; the socket was root-owned as a result.

## Version specifics

- Spring Boot is `4.1.1`. Spring Initializr reports version ids with a `.RELEASE` suffix, which
  is not a Maven Central artifact.
- Boot 4 renames starters: `spring-boot-starter-webmvc`, and `spring-boot-starter-webmvc-test` /
  `spring-boot-starter-validation-test`.
- Boot 4 splits autoconfiguration into per-technology modules. `flyway-core` alone leaves
  `spring.flyway.*` inert and migrations never run; `spring-boot-starter-flyway` is required.
- Boot 4 uses Jackson 3 (`tools.jackson`). `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS` no
  longer exists and setting it fails context startup. ISO-8601 is the default.
- Two Jackson versions are on the classpath: Boot 4 serialises with Jackson 3, swagger-core builds
  schemas with Jackson 2. swagger-core cannot see `spring.jackson.property-naming-strategy`, so
  `OpenApiConfig` supplies a `ModelResolver` with a matching mapper. `OpenApiContractTest` fails if
  the two diverge.
- springdoc 3.x targets Boot 4; 2.x targets Boot 3.
- Spring AI 2.x targets Boot 4; 1.1.x targets Boot 3.5.
- Testcontainers versions are not managed by the Boot 4 parent, and 2.x prefixes every module:
  `testcontainers-postgresql`, `testcontainers-junit-jupiter`.
- `LocalServerPort` is in `org.springframework.boot.test.web.server`.
- `@Validated` on a controller class routes `@RequestParam` violations through
  `ConstraintViolationException` and yields 500. Spring 7 method validation returns 400 without it.
- A bare `@ExceptionHandler(Exception.class)` intercepts framework exceptions and turns a failed
  `@Valid` check into a 500. `ApiExceptionHandler` extends `ResponseEntityExceptionHandler`.
- `@ConditionalOnProperty` applies to bean definitions, not `@EventListener` methods, where it is
  ignored.
- `HttpServletResponse` has no constant for 429.

## Testing

`src/test/resources/application.yaml` shadows the main configuration rather than merging with it,
so anything the context needs is repeated there. It disables Flyway and the embedding backfill and
supplies an unused datasource URL, keeping the web and contract tests free of a database.

Integration tests set the `integration` profile, which re-enables both, and use Testcontainers
with the pgvector image because V1 creates the vector extension. `@SpringBootTest` does not publish
`ApplicationReadyEvent`, so tests needing embeddings call `DocumentEmbedder.backfill()` directly.

`make docker-smoke` runs the compose stack end-to-end and asserts the three core use cases. The
application requires Postgres, so a single container cannot be smoke tested.

## Deployment

Terraform in `terraform/`; see `terraform/README.md`.

```
merge to main or manual dispatch -> publish-image.yml -> Artifact Registry
  -> Pub/Sub "gcr" -> Cloud Build trigger -> gcloud run deploy -> Cloud Run
```

- Nothing in GCP polls a registry. Cloud Run resolves an image to a digest at deploy time, so
  moving `:latest` has no effect until a deploy is triggered.
- Artifact Registry publishes to a topic named `gcr` and does not create it. Payload fields are
  `action`, `digest` and `tag`.
- `ignore_changes` on the Cloud Run image is required: the push-triggered deployer owns the running
  image after the first apply.
- Four service accounts: publisher, terraform-ci, deployer, runtime. The publisher cannot deploy.
- The first apply must be local. `deploy.yml` authenticates as `terraform-ci`, which Terraform
  creates.
- State is remote in GCS and locks via object generations. The bucket name is a committed literal
  because backends cannot take variables. `scripts/bootstrap-tfstate.sh` creates it.
- Terraform providers use the shared plugin cache configured in `~/.terraformrc`.
- Email notification channels created through the API are unverified and deliver nothing until
  verified with a code sent by `notificationChannels.sendVerificationCode`.
- Cloud SQL instances default to ENTERPRISE_PLUS, which rejects shared-core tiers; the edition is
  pinned to ENTERPRISE.
- Migrations V1 to V4 have been applied in production. Editing them changes their checksum and
  fails Flyway validation.

## Conventions

- Packages: `controllers`, `dto`, `search`, `store`, `observability`, `config`.
- DTOs are records carrying validation and `@Schema` annotations.
- JSON is snake_case via `spring.jackson.property-naming-strategy`.
- `POST` returns 201 with a `Location` header. Search returns 200 with an array, empty when
  nothing matches.
- Search hits are a tagged union of `type`, `score` and one of `client` or `document`.
- Structured logs are emitted as one JSON object per line by `CloudLoggingFormatter`; request
  context is carried in the MDC.
