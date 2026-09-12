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
     * including inside an email (`neviswealth` in `john.doe@neviswealth.com`, which full-text
     * cannot do — the parser treats the whole address as one token). Full-text then adds
     * stemming over the prose fields, so "pensions" finds "pension".
     *
     * <p>Ranked by word_similarity rather than similarity: the latter normalises over the whole
     * string, so a short query against a long email scores badly (0.48 vs 1.00 for
     * "neviswealth").
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
     * <p>This is the exact-terms half of document search — an account number, a reference code.
     * The semantic half (vector distance over embeddings) is what connects "address proof" to a
     * document that says "utility bill", and is not implemented yet.
     */
    public List<Scored<DocumentResponse>> findDocuments(String query, int limit) {
        String sql = """
                SELECT id, client_id, title, content, created_at,
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
     * Escapes LIKE wildcards. The value is bound, so injection is not the concern — but an
     * unescaped {@code %} or {@code _} in a user's query would silently act as a wildcard.
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
}
