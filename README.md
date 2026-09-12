# nevis-interview — Search API

Take-home assignment for Nevis: a search API over **clients** and **client documents** for a
WealthTech advisor platform.

An advisor needs to find things two different ways, and those two ways need different machinery:

| Query | Should return | Because |
|---|---|---|
| `NevisWealth` | the client `john.doe@neviswealth.com` | **lexical** — case-insensitive substring of the email domain |
| `address proof` | a document containing *"utility bill"* | **semantic** — the two phrases share no characters, so keyword matching cannot connect them |

> ### Status: schema and plumbing in place, search not implemented
>
> The endpoints validate their input and return correctly-shaped responses, and there is a real
> Postgres schema with the indexes both search paths need. What is **not** built yet: the
> repositories that read and write those tables, the embedding model, and the search queries
> themselves. `GET /search` still returns `[]` for every query. This documents the contract.

## Requirements

- **Java 25** (the build will refuse anything older with an actionable message)
- **Docker + Compose** — for the local Postgres. Not needed to run `make test`, which is
  deliberately database-free
- Maven is **not** required — use the committed wrapper (`./mvnw`)

## Quick start

```sh
docker compose up --build     # API on :8080, Postgres on :5432
```

That is the whole local setup. The app applies its own Flyway migrations at startup, so the
database provisions itself on first run — no init script, no manual `psql`.

```sh
make          # run all tests (default target; needs no database)
make run      # start the API alone on :8080 — expects Postgres to be reachable
make help     # list every target and the resolved settings
```

`make run` connects to `localhost:5432` as `search_api`/`search_api` by default, which matches
compose — so `docker compose up db` plus `make run` is a workable loop if you want the app on
the host and only the database in a container.

If your `java` is not 25, point `make` at one explicitly:

```sh
make JDK=/path/to/jdk-25 test
```

The variable is `JDK`, **not** `JAVA_HOME` — an inherited `JAVA_HOME` from an older JDK would
otherwise win silently and produce a confusing compile failure.

## Web UI

`http://localhost:8080/` serves a search page: a centred box and two buttons, **Find Documents**
and **Find Clients**. Each button calls its own endpoint, and the chosen button decides which of
the two search strategies runs.

Server-rendered with Thymeleaf; [htmx](https://htmx.org) swaps the results fragment in without a
page reload. htmx is **vendored** at `/vendor/htmx.min.js` rather than loaded from a CDN, so the
page works with no outbound network access.

The UI and the JSON API share one `SearchService`, so the page cannot drift from what the API
reports. UI routes live under `/ui/**`, return HTML fragments, and are excluded from the OpenAPI
document — which describes the API, not the page.

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

### `GET /search/clients?q={query}` · `GET /search/documents?q={query}`

The same contract as `/search`, restricted to one kind. These are what the UI's two buttons call.

| Endpoint | Strategy |
|---|---|
| `/search/clients` | lexical — trigram over email, name, description |
| `/search/documents` | semantic — cosine distance over content embeddings |

Same status codes as `/search`: `200` with a possibly-empty array, `400` on a blank `q`.

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

## Data model

Created by [`V1__init.sql`](src/main/resources/db/migration/V1__init.sql), applied by Flyway at
startup.

```
clients     id uuid pk, first_name, last_name, email citext unique,
            description, social_links text[], created_at
documents   id uuid pk, client_id uuid fk -> clients on delete cascade,
            title, content, summary, embedding vector(384), created_at
```

`embedding` is nullable so a document can be stored before it has been embedded. `citext` makes
email comparison case-insensitive without scattering `lower()` through every query.

## How search works

The two cases in the brief need different machinery, so they are two queries with two ranking
functions rather than one clever query attempting both.

**Clients — lexical.** A trigram GIN index over the concatenated searchable text:

```sql
CREATE INDEX clients_trgm_idx ON clients
    USING gin ((first_name || ' ' || last_name || ' ' || email || ' ' || coalesce(description, '')) gin_trgm_ops);
```

This is what makes `?q=NevisWealth` match `john.doe@neviswealth.com` — an `ILIKE '%neviswealth%'`
can use this index, and `word_similarity()` ranks the hits.

> Worth knowing why full-text search is *not* the tool for that case: Postgres' default parser
> treats `john.doe@neviswealth.com` as a single `email` token, so a `tsquery` for `neviswealth`
> never matches it. Full-text search earns its place on prose — the `description` field — not on
> identifiers, and there is a separate `tsvector` index for exactly that.

**Documents — semantic.** `"address proof"` and `"utility bill"` share no characters, so no
lexical index can connect them. Each document's content is embedded once on write into a
384-number vector; the query is embedded the same way at search time; hits are ranked by cosine
distance:

```sql
SELECT id, title FROM documents ORDER BY embedding <=> $1 LIMIT 10;
```

The HNSW index (`vector_cosine_ops`) is built on the empty table, which is the cheap moment —
unlike `ivfflat`, which needs representative data present before it can choose sensible cluster
centroids. 384 dimensions sits well inside pgvector's 2000-dimension HNSW ceiling.

There is also a `tsvector` index over document text, because exact terms — an account number, a
reference code — are precisely what embeddings are worst at.

### Embeddings run locally

`all-MiniLM-L6-v2` via ONNX Runtime, in-process, with the weights baked into the image. No
external embedding or LLM API, no API key, and no network call at request time — the whole stack
runs offline from `docker compose up`.

## Testing

```sh
make test      # all tests — no database or Docker required
make ci        # what CI runs: tests, image build, image smoke test
```

The tests are deliberately **database-free**: `src/test/resources/application.yaml` disables Flyway
and stops the connection pool attempting a connection during context startup, so `make test` runs
anywhere. Tests that exercise SQL should use Testcontainers with `@ServiceConnection`, which
overrides those settings per test.

> That file **shadows** `src/main/resources/application.yaml` rather than merging with it — Spring
> does not combine same-named config files. Anything the context needs has to be repeated there,
> which is why it carries a datasource URL that nothing ever connects to.

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
docker compose up --build   # the whole local stack: API + Postgres
make docker-build           # build nevis/search-api:dev
make docker-push            # build and push to Artifact Registry (triggers a deploy)
make docker-smoke           # boot the built image and assert it serves traffic
```

`docker-compose.yml` runs `pgvector/pgvector:0.8.6-pg16` alongside the API. The database
healthcheck is `pg_isready` rather than a TCP probe: Postgres accepts connections briefly during
initialisation while still rejecting queries, which would let the API start and then fail its
migrations. The API waits on `condition: service_healthy`.

Multi-stage build: `eclipse-temurin:25-jdk-noble` compiles and splits the Spring Boot fat jar into
layers with `-Djarmode=tools ... extract --layers`, and `25-jre-noble` runs them as an unprivileged
user. Dependencies resolve before `src/` is copied, so editing source does not re-download Maven,
and dependency layers are not re-pushed when only application code changes.

Pass JVM flags with `JAVA_TOOL_OPTIONS` — the JVM reads it natively, so the entrypoint stays
shell-free and signals are handled correctly.

> If the Docker socket is root-owned with no `docker` group, every docker target fails fast with
> the fix printed rather than hanging. Run them as `make docker-build DOCKER="sudo docker"`, or
> create the group so no sudo is needed at all.

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

**Database.** Cloud SQL Postgres 16 on a private IP, reached over direct VPC egress — the instance
has no public address. The password is generated by Terraform, stored in Secret Manager, and
injected into the container via `secret_key_ref`, so it is not readable from the service
definition. `enable_cloud_sql` defaults to **true**: the app runs Flyway at startup and exits if it
cannot reach a database, so with it disabled the service crash-loops rather than degrading. It
bills hourly from creation.

**Memory is 2 GiB, not 1.** The JVM shares the instance with ONNX Runtime and the embedding model
weights. At 1 GiB the container starts fine and is OOM-killed on the first embed, which is a much
harder failure to read than one at boot.

Apply Terraform **before** pushing a new image: an image that boots with no `DB_URL` will
crash-loop.

Cloud Run rather than Compute Engine because a GCE VM or managed instance group cannot
redeploy itself when an image is pushed — the rollout would need driving externally
anyway, and the VM bills whether or not traffic arrives.

## Layout

```
src/main/java/com/nevis/search/
    SearchApiApplication.java
    controllers/            HTTP layer — JSON API and the HTML UI routes
    dto/                    request/response records
    search/                 SearchService, shared by the API and the UI
    config/                 OpenAPI metadata
src/main/resources/
    application.yaml        datasource, Flyway, Jackson, springdoc
    db/migration/           Flyway migrations — schema, extensions, indexes
    templates/              Thymeleaf page and result fragment
    static/vendor/          htmx, vendored rather than CDN-loaded
docker-compose.yml          local stack: API + Postgres/pgvector
Dockerfile                  multi-stage, layered, non-root
Makefile                    single entry point for build, test, docker, terraform
scripts/                    image smoke test, state-bucket bootstrap
terraform/                  GCP: Cloud Run, Cloud SQL, registry, push-triggered deploy
.github/workflows/          test, publish-image, deploy
```

DTOs are records; validation annotations live on them. `snake_case` comes from one Jackson property
(`spring.jackson.property-naming-strategy`) rather than per-field `@JsonProperty`.


