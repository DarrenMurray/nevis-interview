-- Schema for clients, their documents, and the two search paths.
--
-- Extensions first. On Cloud SQL these are allowlisted and creatable by the instance's
-- user; locally the pgvector image ships them.
CREATE EXTENSION IF NOT EXISTS vector;    -- similarity search over embeddings
CREATE EXTENSION IF NOT EXISTS pg_trgm;   -- substring/fuzzy match for client lookup
CREATE EXTENSION IF NOT EXISTS citext;    -- case-insensitive email without lower() everywhere

CREATE TABLE clients (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    first_name   text        NOT NULL,
    last_name    text        NOT NULL,
    email        citext      NOT NULL UNIQUE,
    description  text,
    social_links text[]      NOT NULL DEFAULT '{}',
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE documents (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id  uuid        NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    title      text        NOT NULL,
    content    text        NOT NULL,
    summary    text,
    -- 384 dimensions: all-MiniLM-L6-v2's output size. Well inside pgvector's 2000-dim
    -- HNSW ceiling. Nullable so a document can be stored before it is embedded.
    embedding  vector(384),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX documents_client_id_idx ON documents (client_id);

-- --- Client search: lexical -------------------------------------------------------
-- Trigram GIN makes `?q=NevisWealth` match `john.doe@neviswealth.com`: ILIKE '%...%' can
-- use this index and word_similarity() ranks it. Not tsvector: Postgres' default parser
-- treats the whole email as one token, so a tsquery for 'neviswealth' never matches.
CREATE INDEX clients_trgm_idx ON clients
    USING gin ((first_name || ' ' || last_name || ' ' || email || ' ' || coalesce(description, '')) gin_trgm_ops);

-- Full-text over the prose fields, where stemming and ranking genuinely help.
CREATE INDEX clients_fts_idx ON clients
    USING gin (to_tsvector('english', first_name || ' ' || last_name || ' ' || coalesce(description, '')));

-- --- Document search: semantic ----------------------------------------------------
-- HNSW with cosine distance. Built on an empty table, which is the cheap moment to do
-- it; the alternative (ivfflat) needs representative data present before it can pick
-- sensible cluster centroids.
CREATE INDEX documents_embedding_idx ON documents
    USING hnsw (embedding vector_cosine_ops);

-- Full-text over document text as a lexical complement to the vector search: exact terms
-- ("account 8891234") are precisely what embeddings are worst at.
CREATE INDEX documents_fts_idx ON documents
    USING gin (to_tsvector('english', title || ' ' || content));
