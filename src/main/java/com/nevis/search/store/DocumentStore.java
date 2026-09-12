package com.nevis.search.store;

import com.nevis.search.dto.CreateDocumentRequest;
import com.nevis.search.dto.DocumentResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

/** Writes for documents. */
@Repository
public class DocumentStore {

    private final JdbcClient jdbc;

    DocumentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public DocumentResponse insert(String clientId, CreateDocumentRequest request) {
        String sql = """
                INSERT INTO documents (client_id, title, content)
                VALUES (CAST(:clientId AS uuid), :title, :content)
                RETURNING id, client_id, title, content, created_at
                """;

        return jdbc.sql(sql)
                .param("clientId", clientId)
                .param("title", request.title())
                .param("content", request.content())
                .query((rs, rowNum) -> new DocumentResponse(
                        rs.getString("id"),
                        rs.getString("client_id"),
                        rs.getString("title"),
                        rs.getString("content"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .single();
    }
}
