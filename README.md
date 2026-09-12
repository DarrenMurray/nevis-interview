# nevis-interview

Take-home assignment for Nevis: a search API over **clients** and **documents**.

### Preview the App: https://search-api-mjikdl7cpq-nw.a.run.app/

Example **client** search - `NevisWealth`

Example **document** search - `address proof`

More to try against the [sample dataset](src/main/resources/db/migration/V2__seed.sql):
`pension`, `farm`, `retirement income`, `inheritance tax`, `8891234`.

### Technologies

|  | |
|---|---|
| **Java 25**, **Spring Boot 4.1** | build refuses an older JDK with an actionable message |
| **PostgreSQL 16** + **pgvector**, **pg_trgm**, **citext** | vector, trigram and case-insensitive matching |
| **Flyway** | schema and demo data, applied at startup |
| **Spring AI** + **ONNX Runtime** (`all-MiniLM-L6-v2`) | embeddings in-process  |
| **Thymeleaf** + **htmx** | server-rendered UI, htmx vendored rather than CDN-loaded |
| **springdoc** | OpenAPI 3.1 generated from annotations |
| **Testcontainers** | integration tests against real Postgres |
| **Docker Compose** | local stack; needed for `make test` but not for the build |
| **Terraform**, **Cloud Run**, **Cloud SQL** | deployment, redeployed on image push |

### Directory Tree

```
src/main/java/com/nevis/search/
    SearchApiApplication.java
    controllers/            HTTP layer - JSON API and the HTML UI routes
    dto/                    request/response records
    search/                 SearchService, shared by the API and the UI
    config/                 OpenAPI metadata
src/main/resources/
    application.yaml        datasource, Flyway, Jackson, springdoc
    db/migration/           Flyway migrations - schema, extensions, indexes
    templates/              Thymeleaf page and result fragment
    static/vendor/          htmx, vendored rather than CDN-loaded
docker-compose.yml          local stack: API + Postgres/pgvector
Dockerfile                  multi-stage, layered, non-root
Makefile                    single entry point for build, test, docker, terraform
scripts/                    image smoke test, state-bucket bootstrap
terraform/                  GCP: Cloud Run, Cloud SQL, registry, push-triggered deploy
.github/workflows/          test, publish-image, deploy
```

## Developer Guide

### Local setup

```sh
docker compose up --build     # API on :8080, Postgres on :5432
```

The app applies its own Flyway migrations at startup, so the
database provisions itself on first run - no init script, no manual `psql`.

```sh
make          # run all tests (default target; needs no database)
make run      # start the API alone on :8080 - expects Postgres to be reachable
make help     # list every target and the resolved settings
```



#### UI

`http://localhost:8080/` serves the main search page


## API

Interactive documentation is generated from annotations and served by the running app:

| | |
|---|---|
| **Swagger UI** | <http://localhost:8080/swagger-ui.html> |
| **OpenAPI 3.1 JSON** | <http://localhost:8080/v3/api-docs> |
| **OpenAPI 3.1 YAML** | <http://localhost:8080/v3/api-docs.yaml> |

| Verb | Path | Purpose | Body / Params | Success | Errors |
|---|---|---|---|---|---|
| `POST` | `/clients` | Create a client | `first_name`, `last_name`, `email` required; `description`, `social_links` optional | `201` + `Location` | `400` invalid email or missing field · `409` duplicate email (case-insensitive) |
| `POST` | `/clients/{id}/documents` | Attach a document; embedded on write, so searchable immediately | `title`, `content` both required | `201` + `Location` | `400` blank field · `404` unknown client |
| `GET` | `/search` | Both kinds, ranked together | `q` (required) | `200` array | `400` blank `q` |
| `GET` | `/search/clients` | Clients only - lexical (trigram + full-text) | `q` (required) | `200` array | `400` blank `q` |
| `GET` | `/search/documents` | Documents only - semantic + lexical | `q` (required) | `200` array | `400` blank `q` |


```json
{ "type": "document", "score": 0.299, "document": { "id": "...", "title": "Utility Bill - March 2026", "...": "..." } }
```

`type` is `client` or `document`, and exactly one of those two keys is present. `score` is 0-1:
trigram similarity for clients, cosine similarity for documents.

### Creating a client

```sh
curl -X POST localhost:8080/clients -H 'Content-Type: application/json' -d '{
  "first_name": "Ada",
  "last_name": "Lovelace",
  "email": "ada.lovelace@neviswealth.com",
  "description": "Semi-retired; conservative income portfolio.",
  "social_links": ["https://www.linkedin.com/in/adalovelace"]
}'
```

`201 Created`, `Location: /clients/403cf1ad-cb6c-4ab0-9872-ac12d0f16cea`

```json
{
  "id": "403cf1ad-cb6c-4ab0-9872-ac12d0f16cea",
  "first_name": "Ada",
  "last_name": "Lovelace",
  "email": "ada.lovelace@neviswealth.com",
  "description": "Semi-retired; conservative income portfolio.",
  "social_links": ["https://www.linkedin.com/in/adalovelace"]
}
```

### Creating a document

```sh
curl -X POST localhost:8080/clients/403cf1ad-cb6c-4ab0-9872-ac12d0f16cea/documents \
  -H 'Content-Type: application/json' -d '{
  "title": "Home Insurance Schedule",
  "content": "Buildings and contents cover for the property at 9 Larch Way. The policyholder is recorded as living at the insured address throughout the period of cover. Sum insured GBP 450,000. Excess GBP 250 per claim. Renewal date 14 August 2026."
}'
```

`201 Created`, `Location: /clients/{client_id}/documents/377da3c1-37d2-4152-bc18-086d135e3430`

```json
{
  "id": "377da3c1-37d2-4152-bc18-086d135e3430",
  "client_id": "403cf1ad-cb6c-4ab0-9872-ac12d0f16cea",
  "title": "Home Insurance Schedule",
  "summary": "Buildings and contents cover for the property at 9 Larch Way. The policyholder is recorded as living at the insured address throughout the period of cover.",
  "content": "Buildings and contents cover for the property at 9 Larch Way. ...",
  "created_at": "2026-09-12T19:36:51.212208Z"
}
```

The document is embedded and summarised **during** the request, so it is searchable as soon as
this returns. `summary` is extractive: every sentence in it appears in the document itself.

### Rate limiting

**60 requests per minute, globally across the service**, on every endpoint except static assets
and the API docs.

The limit is deliberately aggressive. This runs on a personal Google Cloud account, and the API is
public and unauthenticated, so the ceiling exists to bound the bill rather than to be fair between
callers. There is no trustworthy identity to meter against, and per-IP limiting would be both
trivially defeated and useless against a spend-out from many addresses, so one global bucket it is.

Tokens **refill continuously**, roughly one per second, rather than resetting on the minute. Once
exhausted you are limited until enough have accrued, and a rejected request does **not** consume a
token, so retrying while limited does not dig the hole deeper.

| | |
|---|---|
| Exceeded | `429 Too Many Requests` |
| `Retry-After` | seconds until a token is available |
| `X-RateLimit-Limit` | configured requests per minute |
| `X-RateLimit-Remaining` | tokens currently in the bucket |

```json
{
  "status": 429,
  "error": "Too Many Requests",
  "message": "Rate limit of 60 requests per minute exceeded. Tokens refill continuously; retry in 17 second(s).",
  "retry_after_seconds": 17
}
```

Configurable with `search.rate-limit.requests-per-minute`. One caveat: the bucket is per
container, so with Cloud Run scaling to N instances the effective ceiling is N times the rate.
Max instances is kept low for that reason.

### Logging

Every request emits one structured JSON line, plus a line per search and per failure. Fields are
uniform, so a search is traceable end to end by `request_id`:

```json
{"severity":"INFO","message":"Request handled","http_method":"GET","http_path":"/search",
 "http_status":"200","duration_ms":"314","client_ip":"172.18.0.1","user_agent":"curl/7.81.0",
 "search_term":"address proof","request_id":"2e599aea-44fe-4f87-93c9-139de0e43448"}
```

`severity` and `message` are top-level because that is what Cloud Logging keys off; Spring Boot's
built-in ECS and Logstash formats would land every line as DEFAULT severity, losing the
distinction between info and error. Every MDC field is promoted to a top-level key, so
`search_term` and `client_ip` are queryable in Logs Explorer rather than buried in a string.

`X-Request-Id` is echoed on every response, and is accepted on the way in so a trace survives
across services. Expected failures log at `WARNING` without a stack trace; unexpected ones at
`ERROR` with one, which is what Cloud Error Reporting picks up. Search terms are truncated at 200
characters, and static assets are not logged at all.

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

### Demo data

[`V2__seed.sql`](src/main/resources/db/migration/V2__seed.sql) seeds 6 clients and 13 documents.
It is a migration rather than a local-only script, so the deployed demo has content too.

## How search works

The two cases in the brief need different machinery, so they are two queries with two ranking
functions rather than one clever query attempting both.

**Clients - lexical.** A trigram GIN index over the concatenated searchable text:

**Documents - semantic.** `"address proof"` and `"utility bill"` share no characters, so no
lexical index can connect them. Each document's content is embedded once on write into a
384-number vector; the query is embedded the same way at search time; hits are ranked by cosine
distance:


## Testing

```sh
make test  
make ci
```
