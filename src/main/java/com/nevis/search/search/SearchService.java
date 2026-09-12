package com.nevis.search.search;

import com.nevis.search.dto.SearchResultResponse;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Search across clients and documents, shared by the JSON API and the UI.
 *
 * <p>Two strategies, kept separate: clients match lexically (substring and stemmed word over
 * email, name, description), documents by content.
 */
@Service
public class SearchService {

    /** Cap on rows per search. Keeps a broad query from returning the whole table. */
    private static final int LIMIT = 20;

    private final SearchRepository repository;

    SearchService(SearchRepository repository) {
        this.repository = repository;
    }

    /** Clients and documents together, ranked into one list. */
    public List<SearchResultResponse> search(String query) {
        return Stream.concat(searchClients(query).stream(), searchDocuments(query).stream())
                .sorted(Comparator.comparingDouble(SearchResultResponse::score).reversed())
                .toList();
    }

    public List<SearchResultResponse> searchClients(String query) {
        return repository.findClients(query.trim(), LIMIT).stream()
                .map(hit -> SearchResultResponse.ofClient(hit.value(), round(hit.score())))
                .toList();
    }

    public List<SearchResultResponse> searchDocuments(String query) {
        return repository.findDocuments(query.trim(), LIMIT).stream()
                .map(hit -> SearchResultResponse.ofDocument(hit.value(), round(hit.score())))
                .toList();
    }

    /** Scores are for display and ordering; full float precision is noise in the response. */
    private static double round(double score) {
        return Math.round(score * 1000.0) / 1000.0;
    }
}
