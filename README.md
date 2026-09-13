# nevis-interview

Search API over clients and their documents. Two search paths: clients match lexically, documents
match by meaning using locally computed embeddings.

### Preview: https://search-api-mjikdl7cpq-nw.a.run.app/

Client search: `NevisWealth`

Document search: `address proof`

Other queries against the [sample dataset](src/main/resources/db/migration/V2__seed.sql):
`pension`, `farm`, `retirement income`, `inheritance tax`, `8891234`.

### Technologies

| | |
|---|---|
| Java 25, Spring Boot 4.1 | |
| PostgreSQL 16, pgvector, pg_trgm, citext | vector, trigram and case-insensitive matching |
| Flyway | schema and demo data, applied at startup |
| Spring AI, ONNX Runtime, `all-MiniLM-L6-v2` | embeddings computed in-process |
| Thymeleaf, htmx | server-rendered UI |
| springdoc | OpenAPI 3.1 generated from annotations |
| Testcontainers | integration tests against Postgres |
| Docker Compose | local stack |
| Terraform, Cloud Run, Cloud SQL | deployment, redeployed on image push |

### Directory tree

```
src/main/java/com/nevis/search/
    controllers/            HTTP layer, JSON API and UI routes
    dto/                    request and response records
    search/                 search, embedding and summarisation
    store/                  writes for clients and documents
    observability/          rate limiting, request logging, error handling
    config/                 OpenAPI metadata
src/main/resources/
    db/migration/           Flyway migrations
    templates/              Thymeleaf page and result fragment
    static/vendor/          htmx
docker-compose.yml          API and Postgres
Dockerfile                  multi-stage, layered, non-root
Makefile                    build, test, docker and terraform targets
scripts/                    end-to-end smoke test, state bucket bootstrap
terraform/                  Cloud Run, Cloud SQL, registry, push-triggered deploy
.github/workflows/          test, publish-image, deploy
```

## Developer guide

### Local setup

```sh
docker compose up --build     # API on :8080, Postgres on :5432
```

Flyway applies the schema and demo data at startup.

```sh
make          # run all tests
make run      # start the API alone against a reachable Postgres
make help     # list targets and resolved settings
```

### UI

`http://localhost:8080/` serves a search page with separate buttons for client and document
search. Results are swapped in by htmx; document results include a summary of each document and a
summary of the result set.

## API

Generated documentation is served by the running application:

| | |
|---|---|
| Swagger UI | <http://localhost:8080/swagger-ui.html> |
| OpenAPI 3.1 JSON | <http://localhost:8080/v3/api-docs> |
| OpenAPI 3.1 YAML | <http://localhost:8080/v3/api-docs.yaml> |

| Verb | Path | Purpose | Body / Params | Success | Errors |
|---|---|---|---|---|---|
| `POST` | `/clients` | Create a client | `first_name`, `last_name`, `email` required; `description`, `social_links` optional | `201` + `Location` | `400` invalid email or missing field · `409` duplicate email |
| `POST` | `/clients/{id}/documents` | Attach a document, embedded and summarised on write | `title`, `content` required | `201` + `Location` | `400` blank field · `404` unknown client |
| `GET` | `/search` | Clients and documents, ranked together | `q` required | `200` array | `400` blank `q` |
| `GET` | `/search/clients` | Clients only, lexical | `q` required | `200` array | `400` blank `q` |
| `GET` | `/search/documents` | Documents only, semantic and lexical | `q` required | `200` array | `400` blank `q` |

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
  "content": "Buildings and contents cover for the property at 9 Larch Way. The policyholder is recorded as living at the insured address throughout the period of cover."
}'
```

```json
{
  "id": "377da3c1-37d2-4152-bc18-086d135e3430",
  "client_id": "403cf1ad-cb6c-4ab0-9872-ac12d0f16cea",
  "title": "Home Insurance Schedule",
  "summary": "Buildings and contents cover for the property at 9 Larch Way.",
  "content": "Buildings and contents cover for the property at 9 Larch Way. ...",
  "created_at": "2026-09-12T19:36:51.212208Z"
}
```


### Rate limiting

60 requests per minute, applied globally across the service, on every endpoint except static
assets and the API documentation. The service is public and unauthenticated, so the limit bounds
total load rather than metering individual callers.

Tokens refill continuously at roughly one per second rather than resetting on a fixed window. A
rejected request does not consume a token.

| | |
|---|---|
| Exceeded | `429 Too Many Requests` |
| `Retry-After` | seconds until a token is available |
| `X-RateLimit-Limit` | configured requests per minute |
| `X-RateLimit-Remaining` | tokens in the bucket |

```json
{
  "status": 429,
  "error": "Too Many Requests",
  "message": "Rate limit of 60 requests per minute exceeded. Tokens refill continuously; retry in 17 second(s).",
  "retry_after_seconds": 17
}
```

## Data model

Created by [`V1__init.sql`](src/main/resources/db/migration/V1__init.sql).

```
clients          id uuid pk, first_name, last_name, email citext unique,
                 description, social_links text[], created_at
documents        id uuid pk, client_id uuid fk -> clients on delete cascade,
                 title, content, summary, embedding vector(384), created_at
document_chunks  id bigserial pk, document_id uuid fk, chunk_index,
                 content, embedding vector(384)
```

`embedding` is nullable so a document can be stored before it is embedded. `citext` makes email
comparison case-insensitive.

### Demo data

[`V2__seed.sql`](src/main/resources/db/migration/V2__seed.sql) and
[`V4__longer_documents.sql`](src/main/resources/db/migration/V4__longer_documents.sql) load 6
clients and 13 documents.

## Testing

```sh
make test            # unit and integration tests
make docker-smoke    # end-to-end against the compose stack
make ci              # both, plus the image build
```

Integration tests use Testcontainers and require Docker. `make docker-smoke` brings up the compose
stack and asserts the three core use cases against the running containers:

| | |
|---|---|
| Client search | `q=NevisWealth` returns `john.doe@neviswealth.com`; `q=pension` matches on description alone |
| Document search | no document contains "address proof"; that query returns the utility bill; unrelated queries return nothing |
| Summaries | every document result carries a summary, and summary text appears in its document |
