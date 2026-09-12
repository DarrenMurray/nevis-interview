# nevis-interview — Search API

Take-home assignment for Nevis: a search API over **clients** and **client documents** for a
WealthTech advisor platform.

An advisor needs to find things two different ways, and those two ways need different machinery:

| Query | Should return | Because |
|---|---|---|
| `NevisWealth` | the client `john.doe@neviswealth.com` | **lexical** — case-insensitive substring of the email domain |
| `address proof` | a document containing *"utility bill"* | **semantic** — the two phrases share no characters, so keyword matching cannot connect them |

> ### Status: skeleton
>
> The endpoints below exist, validate their input, and return correctly-shaped responses. They do
> **not** persist anything, and `GET /search` always returns `[]`. This documents the contract.

## Requirements

- **Java 25** (the build will refuse anything older with an actionable message)
- **Docker** — only for `make docker-*`; not needed to run tests or the app
- Maven is **not** required — use the committed wrapper (`./mvnw`)

## Quick start

```sh
make          # run all tests (default target)
make run      # start the API on http://localhost:8080
make help     # list every target and the resolved settings
```

If your `java` is not 25, point `make` at one explicitly:

```sh
make JDK=/path/to/jdk-25 test
```

The variable is `JDK`, **not** `JAVA_HOME` — an inherited `JAVA_HOME` from an older JDK would
otherwise win silently and produce a confusing compile failure.

## API

Interactive documentation is generated from annotations and served by the running app:

| | |
|---|---|
| **Swagger UI** | <http://localhost:8080/swagger-ui.html> |
| **OpenAPI 3.1 JSON** | <http://localhost:8080/v3/api-docs> |
| **OpenAPI 3.1 YAML** | <http://localhost:8080/v3/api-docs.yaml> |

There is no checked-in spec file to drift out of date — the document is built from `@Operation`,
`@ApiResponse` and `@Schema` annotations on the controllers and DTOs. A contract test asserts the
generated schemas match what the endpoints actually return (see [Testing](#testing)).

The reference below is the same information in prose form.

Base URL `http://localhost:8080`. All request and response bodies are `application/json`, and all
field names are **`snake_case`**.

---

### `POST /clients`

Create a client.

**Body** — `first_name`, `last_name` and `email` are required; `description` and `social_links` are
optional.

```sh
curl -X POST localhost:8080/clients \
  -H 'Content-Type: application/json' \
  -d '{
        "first_name": "John",
        "last_name": "Doe",
        "email": "john.doe@neviswealth.com",
        "description": "Retired engineer; cautious, income-focused portfolio.",
        "social_links": ["https://www.linkedin.com/in/johndoe"]
      }'
```

**`201 Created`** with `Location: /clients/f7231496-3fcc-474c-bf02-930aecbd37af`

```json
{
  "id": "f7231496-3fcc-474c-bf02-930aecbd37af",
  "first_name": "John",
  "last_name": "Doe",
  "email": "john.doe@neviswealth.com",
  "description": "Retired engineer; cautious, income-focused portfolio.",
  "social_links": ["https://www.linkedin.com/in/johndoe"]
}
```

| Status | When |
|---|---|
| `201` | created |
| `400` | missing required field, or `email` is not a valid address |
| `409` | *(planned)* a client with that email already exists |

---

### `POST /clients/{id}/documents`

Attach a document to a client. `title` and `content` are both required.

```sh
curl -X POST localhost:8080/clients/f7231496-3fcc-474c-bf02-930aecbd37af/documents \
  -H 'Content-Type: application/json' \
  -d '{
        "title": "Utility Bill - March 2026",
        "content": "Thames Water. Account 8891234. Service address: 12 Acacia Avenue, London N1 4TG. Billing period 01-31 March 2026."
      }'
```

**`201 Created`** with `Location: /clients/{client_id}/documents/{id}`

```json
{
  "id": "17a32d53-eb66-4364-a8aa-f690b8e79868",
  "client_id": "f7231496-3fcc-474c-bf02-930aecbd37af",
  "title": "Utility Bill - March 2026",
  "content": "Thames Water. Account 8891234. Service address: 12 Acacia Avenue, London N1 4TG. Billing period 01-31 March 2026.",
  "created_at": "2026-09-12T14:20:35.754252647+01:00"
}
```

| Status | When |
|---|---|
| `201` | created |
| `400` | `title` or `content` missing or blank |
| `404` | *(planned)* no client with that `id` |

---

### `GET /search?q={query}`

Search across clients and documents. `q` is required and must not be blank.

Returns a **flat array of hits of both kinds**. Each hit is a tagged union: `type` discriminates,
and exactly one of `client` or `document` is present. The OpenAPI spec leaves search items as a bare
object and invites extending responses, so `type` and `score` are additions — without a
discriminator a client and a document are indistinguishable to a consumer.

```sh
curl 'localhost:8080/search?q=NevisWealth'
```

**`200 OK`** — currently always `[]`. The intended shape once search is implemented:

```json
[
  {
    "type": "client",
    "score": 0.91,
    "client": {
      "id": "f7231496-3fcc-474c-bf02-930aecbd37af",
      "first_name": "John",
      "last_name": "Doe",
      "email": "john.doe@neviswealth.com",
      "description": "Retired engineer; cautious, income-focused portfolio.",
      "social_links": ["https://www.linkedin.com/in/johndoe"]
    }
  },
  {
    "type": "document",
    "score": 0.78,
    "document": {
      "id": "17a32d53-eb66-4364-a8aa-f690b8e79868",
      "client_id": "f7231496-3fcc-474c-bf02-930aecbd37af",
      "title": "Utility Bill - March 2026",
      "content": "Thames Water. Account 8891234. Service address: 12 Acacia Avenue, London N1 4TG...",
      "created_at": "2026-09-12T14:20:35.754252647+01:00"
    }
  }
]
```

| Status | When |
|---|---|
| `200` | results, **or an empty array** — no match is a valid result, not a missing resource, so never `404` |
| `400` | `q` absent, empty, or whitespace only |

Verified today:

```
GET /search?q=NevisWealth      -> 200 []
GET /search?q=address%20proof  -> 200 []
GET /search                    -> 400
GET /search?q=                 -> 400
```

---

### Errors

Validation failures return Spring's default error body:

```json
{
  "timestamp": "2026-09-12T13:20:25.543Z",
  "status": 400,
  "error": "Bad Request",
  "path": "/clients"
}
```

Per-field validation detail is not yet exposed.

## How search will work

**Clients — lexical.** Case-insensitive substring across `email`, `first_name`, `last_name` and
`description`, ranked by trigram similarity. `NevisWealth` matches `john.doe@neviswealth.com`
because the normalised query is a substring of the email.

**Documents — semantic.** `"address proof"` and `"utility bill"` have no words in common, so this
cannot be keyword matching. Each document's content is converted once, on write, into a 384-number
vector by an embedding model; the query is converted the same way at search time; and hits are
ranked by cosine distance between those vectors. Text with similar *meaning* lands close together
in that space, which is what bridges the two phrases.

The model (`all-MiniLM-L6-v2`, ~80MB) is bundled in the image and runs **in-process**. There is no
external LLM or embedding API, no API key, and no network call at request time — the whole stack
runs offline.

## Testing

```sh
make test      # all tests
make ci        # what CI runs: tests, image build, image smoke test
```

`OpenApiContractTest` is the interesting one: it asserts the OpenAPI document is 3.1, covers all
three endpoints, advertises the right required fields, and — crucially — that every documented
schema property matches the field names the API really serves. Boot 4 serialises with Jackson 3
while swagger-core builds schemas with its own Jackson 2, so the document silently drifts to
`firstName` while the API returns `first_name` unless corrected. That test fails if the correction
is ever removed.

CI is [`.github/workflows/test.yml`](.github/workflows/test.yml), which runs on pushes to `main`,
on pull requests, and on manual dispatch. Two parallel jobs, both driven through `make`:

- **tests** — JDK 25 via `setup-java`, then `make test`
- **image** — `make docker-build`, then `make docker-smoke`, which boots the built image and asserts
  `GET /search` returns `200`. This is the only check that exercises the Dockerfile itself; the
  Maven suite never touches it.

## Docker

```sh
make docker-build     # build nevis/search-api:dev
make docker-run       # run it, publishing :8080
make docker-smoke     # boot it and assert it serves traffic
```

Multi-stage build: `eclipse-temurin:25-jdk-noble` compiles and splits the Spring Boot fat jar into
layers with `-Djarmode=tools ... extract --layers`, and `25-jre-noble` runs them as an unprivileged
user. Dependencies resolve before `src/` is copied, so editing source does not re-download Maven,
and dependency layers are not re-pushed when only application code changes.

Pass JVM flags with `JAVA_TOOL_OPTIONS` — the JVM reads it natively, so the entrypoint stays
shell-free and signals are handled correctly.

> On a host where the Docker socket is root-owned and there is no `docker` group, the Makefile
> detects that the daemon is unreachable and falls back to `sudo docker`. Override with
> `make DOCKER=docker ...`.

## Deployment

GCP, defined in [`terraform/`](terraform/) — see [terraform/README.md](terraform/README.md)
for the apply steps. **Not yet applied; nothing is running.**

```
merge to main ─┐
               ├─► publish-image.yml ─► Artifact Registry ─► Pub/Sub "gcr" ─► Cloud Build ─► Cloud Run
manual button ─┘    (:latest + :sha-)
```

- **Publishing** — [`.github/workflows/publish-image.yml`](.github/workflows/publish-image.yml)
  builds and pushes on every merge to `main`, and on demand via **Run workflow** on the
  Actions tab. Always tags `:latest`, plus `:sha-<short>` so a previous build can still be
  named for rollback.
- **Deploying** — Artifact Registry publishes to the `gcr` Pub/Sub topic on push; a
  filtered Cloud Build trigger runs `gcloud run deploy`. Push and deploy are decoupled, so
  an image pushed by hand rolls out like one pushed by CI.
- **Auth** — Workload Identity Federation, scoped to this repository. No service account
  key exists to leak.
- **State** — remote, in a GCS bucket with versioning and lifecycle rules, so local and CI
  runs share one state object and locking is handled by GCS. Create it once with
  `make tf-bootstrap`; the bucket cannot be a Terraform resource because `init` needs it
  to already exist.
- **Infrastructure changes** — [`.github/workflows/deploy.yml`](.github/workflows/deploy.yml)
  plans on pull requests touching `terraform/**`, applies on merge to `main`, and takes a
  manual plan-or-apply dispatch. It applies the saved plan file, so what was reviewed is
  what runs. The first apply must be local, since the workflow's own service account is
  created by Terraform.

Worth knowing: **Cloud Run pins an image digest at deploy time**, so moving `:latest`
alone changes nothing. The Cloud Build trigger is what makes push-to-update real.

Ready for what comes next: a VPC, a reserved private-services range and service-networking
peering are created up front, so Postgres is `enable_cloud_sql = true` rather than a
re-architecture, and a UI can be a second Cloud Run service behind a load balancer.

Cloud Run rather than Compute Engine because a GCE VM or managed instance group cannot
redeploy itself when an image is pushed — the rollout would need driving externally
anyway, and the VM bills whether or not traffic arrives.

## Layout

```
src/main/java/com/nevis/search/
    SearchApiApplication.java
    controllers/            HTTP layer
    dto/                    request/response records
src/main/resources/application.yaml
scripts/docker-smoke.sh     image boot check used by CI
Dockerfile                  multi-stage, layered, non-root
Makefile                    single entry point for build, test, docker
terraform/                  GCP: Cloud Run, Artifact Registry, push-triggered deploy
.github/workflows/          test (build + test) and publish-image (build + push)
```

DTOs are records; validation annotations live on them. `snake_case` comes from one Jackson property
(`spring.jackson.property-naming-strategy`) rather than per-field `@JsonProperty`.


