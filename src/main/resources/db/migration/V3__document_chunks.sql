-- Passage-level embeddings.
--
-- One vector per document dilutes the signal: a utility bill's content is mostly account
-- numbers and balances, so the sentence that actually establishes residence barely moves
-- the document's average. Embedding passages and scoring a document by its best passage
-- is what makes "address proof" reach it.

CREATE TABLE document_chunks (
    id          bigserial   PRIMARY KEY,
    document_id uuid        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    chunk_index int         NOT NULL,
    content     text        NOT NULL,
    embedding   vector(384) NOT NULL,
    UNIQUE (document_id, chunk_index)
);

CREATE INDEX document_chunks_embedding_idx ON document_chunks
    USING hnsw (embedding vector_cosine_ops);

CREATE INDEX document_chunks_document_id_idx ON document_chunks (document_id);
