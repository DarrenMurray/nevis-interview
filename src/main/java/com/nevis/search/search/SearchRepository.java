package com.nevis.search.search;

import com.nevis.search.dto.ClientResponse;
import com.nevis.search.dto.DocumentResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

/** SQL for the two search paths. */
@Repository
public class SearchRepository {

    private final JdbcClient jdbc;

    SearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Clients matching {@code query} by substring or stemmed word, best first.
     *
     * <p>Two predicates because they catch different things: ILIKE finds a fragment anywhere,
     * including inside an email ("neviswealth" in "john.doe@neviswealth.com", which full-text
     * cannot do - the parser treats the whole address as one token). Full-text then adds
     * stemming over the prose fields, so "pensions" finds "pension".
     *
     * <p>Ranked by word_similarity rather than similarity: the latter normalises over the whole
     * string, so a short query against a long email scores badly (0.48 vs 1.00).
     */
    public List<Scored<ClientResponse>> findClients(String query, int limit) {
        String sql = """
                SELECT id, first_name, last_name, email, description, social_links,
                       GREATEST(
                           word_similarity(:q, first_name || ' ' || last_name),
                           word_similarity(:q, email::text),
                           word_similarity(:q, coalesce(description, ''))
                       ) AS score
                FROM clients
                WHERE (first_name || ' ' || last_name || ' ' || email || ' ' || coalesce(description, ''))
                          ILIKE '%' || :pattern || '%'
                   OR to_tsvector('english', first_name || ' ' || last_name || ' ' || coalesce(description, ''))
                          @@ plainto_tsquery('english', :q)
                ORDER BY score DESC, last_name, first_name
                LIMIT :limit
                """;

        return jdbc.sql(sql)
                .param("q", query)
                .param("pattern", escapeLike(query))
                .param("limit", limit)
                .query((rs, rowNum) -> new Scored<>(mapClient(rs), rs.getDouble("score")))
                .list();
    }

    /**
     * Documents matching {@code query} lexically, best first.
     *
     * <p>The exact-terms half of document search - an account number, a reference code. Meaning
     * is handled separately by {@link #findDocumentsSemantic}.
     */
    public List<Scored<DocumentResponse>> findDocumentsLexical(String query, int limit) {
        String sql = """
                SELECT id, client_id, title, summary, content, created_at,
                       GREATEST(
                           word_similarity(:q, title),
                           word_similarity(:q, content),
                           ts_rank(to_tsvector('english', title || ' ' || content),
                                   plainto_tsquery('english', :q))
                       ) AS score
                FROM documents
                WHERE to_tsvector('english', title || ' ' || content) @@ plainto_tsquery('english', :q)
                   OR (title || ' ' || content) ILIKE '%' || :pattern || '%'
                ORDER BY score DESC, created_at DESC
                LIMIT :limit
                """;

        return jdbc.sql(sql)
                .param("q", query)
                .param("pattern", escapeLike(query))
                .param("limit", limit)
                .query((rs, rowNum) -> new Scored<>(mapDocument(rs), rs.getDouble("score")))
                .list();
    }

    /**
     * Documents ranked by their best-matching passage, nearest first.
     *
     * <p>This is the half that connects "address proof" to a document saying "utility bill".
     * Scoring on the closest chunk rather than a whole-document average is what makes it work:
     * one sentence establishing residence is decisive, but barely registers in the average of a
     * page of account numbers.
     *
     * <p>{@code maxDistance} matters - every vector has some distance to every other, so
     * without a cutoff there is no such thing as "no result".
     */
    public List<Scored<DocumentResponse>> findDocumentsSemantic(
            String queryVector, int limit, double maxDistance) {
        String sql = """
                SELECT d.id, d.client_id, d.title, d.summary, d.content, d.created_at,
                       1 - min(c.embedding <=> CAST(:vec AS vector)) AS score
                FROM documents d
                JOIN document_chunks c ON c.document_id = d.id
                GROUP BY d.id, d.client_id, d.title, d.summary, d.content, d.created_at
                HAVING min(c.embedding <=> CAST(:vec AS vector)) <= :maxDistance
                ORDER BY score DESC
                LIMIT :limit
                """;

        return jdbc.sql(sql)
                .param("vec", queryVector)
                .param("maxDistance", maxDistance)
                .param("limit", limit)
                .query((rs, rowNum) -> new Scored<>(mapDocument(rs), rs.getDouble("score")))
                .list();
    }

    /** Replaces a document's passages. Delete-then-insert keeps re-embedding idempotent. */
    public void replaceChunks(String documentId, List<Chunk> chunks) {
        jdbc.sql("DELETE FROM document_chunks WHERE document_id = CAST(:id AS uuid)")
                .param("id", documentId)
                .update();

        int index = 0;
        for (Chunk chunk : chunks) {
            jdbc.sql("""
                            INSERT INTO document_chunks (document_id, chunk_index, content, embedding)
                            VALUES (CAST(:id AS uuid), :idx, :content, CAST(:vec AS vector))
                            """)
                    .param("id", documentId)
                    .param("idx", index++)
                    .param("content", chunk.content())
                    .param("vec", chunk.vector())
                    .update();
        }
    }

    /**
     * Documents needing embedding: no document vector, or no passages.
     *
     * <p>The chunk check matters when passages are introduced after documents already exist -
     * their document vector is set, so an embedding-only check would skip them and the semantic
     * search would silently have nothing to rank.
     */
    public List<Unembedded> findUnembedded(int limit) {
        String sql = """
                SELECT d.id, d.title, d.content
                FROM documents d
                WHERE d.embedding IS NULL
                   OR NOT EXISTS (SELECT 1 FROM document_chunks c WHERE c.document_id = d.id)
                LIMIT :limit
                """;
        return jdbc.sql(sql)
                .param("limit", limit)
                .query((rs, rowNum) -> new Unembedded(
                        rs.getString("id"), rs.getString("title"), rs.getString("content")))
                .list();
    }

    public void updateSummary(String id, String summary) {
        jdbc.sql("UPDATE documents SET summary = :summary WHERE id = CAST(:id AS uuid)")
                .param("summary", summary)
                .param("id", id)
                .update();
    }

    public void updateEmbedding(String id, String vector) {
        jdbc.sql("UPDATE documents SET embedding = CAST(:vec AS vector) WHERE id = CAST(:id AS uuid)")
                .param("vec", vector)
                .param("id", id)
                .update();
    }

    /**
     * Escapes LIKE wildcards. Values are bound, so injection is not the concern - but an
     * unescaped % or _ in a user's query would silently act as a wildcard.
     */
    private static String escapeLike(String query) {
        return query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static ClientResponse mapClient(ResultSet rs) throws SQLException {
        return new ClientResponse(
                rs.getString("id"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("email"),
                rs.getString("description"),
                toList(rs.getArray("social_links")));
    }

    private static DocumentResponse mapDocument(ResultSet rs) throws SQLException {
        return new DocumentResponse(
                rs.getString("id"),
                rs.getString("client_id"),
                rs.getString("title"),
                rs.getString("summary"),
                rs.getString("content"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private static List<String> toList(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return List.of((String[]) array.getArray());
    }

    /** A row with its relevance score. */
    public record Scored<T>(T value, double score) {
    }

    /** Just the fields the backfill needs. */
    public record Unembedded(String id, String title, String content) {
    }

    /** A passage and its vector, ready to store. */
    public record Chunk(String content, String vector) {
    }
}
