package com.nevis.search.search;

import com.nevis.search.dto.SearchResultResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Search across clients and documents, shared by the JSON API and the UI.
 *
 * <p>Two strategies, kept separate: clients match lexically (substring over email, name,
 * description), documents semantically (vector similarity).
 *
 * <p>All methods are stubs returning no results.
 */
@Service
public class SearchService {

    /** Clients and documents together, ranked into one list. */
    public List<SearchResultResponse> search(String query) {
        return concat(searchClients(query), searchDocuments(query));
    }

    /** Clients only — lexical match. */
    public List<SearchResultResponse> searchClients(String query) {
        // TODO: case-insensitive substring over email/name/description, ranked by trigram.
        return List.of();
    }

    /** Documents only — semantic match. */
    public List<SearchResultResponse> searchDocuments(String query) {
        // TODO: embed the query, rank by cosine distance against stored vectors.
        return List.of();
    }

    private static List<SearchResultResponse> concat(
            List<SearchResultResponse> clients, List<SearchResultResponse> documents) {
        return java.util.stream.Stream.concat(clients.stream(), documents.stream())
                .sorted(java.util.Comparator.comparingDouble(SearchResultResponse::score).reversed())
                .toList();
    }
}
