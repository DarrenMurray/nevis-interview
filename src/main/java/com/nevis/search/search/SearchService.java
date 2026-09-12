package com.nevis.search.search;

import com.nevis.search.dto.SearchResultResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Search across clients and documents.
 *
 * <p>Shared by the JSON API and the HTMX UI so both return the same results — the UI is a
 * different rendering of the same query, not a second implementation of it.
 *
 * <p>Every method is currently a stub returning no results. The two search strategies are
 * deliberately separate because they need different machinery: clients match lexically
 * (substring over email, name, description), while documents match semantically (vector
 * similarity, so "address proof" finds a document saying "utility bill").
 */
@Service
public class SearchService {

    /** Clients and documents together, ranked into one list. */
    public List<SearchResultResponse> search(String query) {
        return concat(searchClients(query), searchDocuments(query));
    }

    /** Clients only — lexical match. */
    public List<SearchResultResponse> searchClients(String query) {
        // TODO: case-insensitive substring over email/first_name/last_name/description,
        // ranked by trigram similarity.
        return List.of();
    }

    /** Documents only — semantic match. */
    public List<SearchResultResponse> searchDocuments(String query) {
        // TODO: embed the query, rank by cosine distance against stored document vectors.
        return List.of();
    }

    private static List<SearchResultResponse> concat(
            List<SearchResultResponse> clients, List<SearchResultResponse> documents) {
        return java.util.stream.Stream.concat(clients.stream(), documents.stream())
                .sorted(java.util.Comparator.comparingDouble(SearchResultResponse::score).reversed())
                .toList();
    }
}
