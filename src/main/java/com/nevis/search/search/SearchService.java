package com.nevis.search.search;

import com.nevis.search.dto.DocumentResponse;
import com.nevis.search.dto.SearchResultResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Search across clients and documents, shared by the JSON API and the UI.
 *
 * <p>Clients match lexically. Documents match both ways and the results are merged: embeddings
 * find meaning ("address proof" -> a utility bill), lexical finds exact strings (an account
 * number), and neither can do the other's job.
 */
@Service
public class SearchService {

    /** Cap on rows per search, so a broad query cannot return the whole table. */
    private static final int LIMIT = 20;

    private final SearchRepository repository;
    private final DocumentEmbedder embedder;
    private final double maxDistance;

    SearchService(SearchRepository repository,
                  DocumentEmbedder embedder,
                  @Value("${search.semantic.max-distance:0.75}") double maxDistance) {
        this.repository = repository;
        this.embedder = embedder;
        this.maxDistance = maxDistance;
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

    /**
     * Documents from both strategies, de-duplicated.
     *
     * <p>A document can match semantically and lexically at once; it keeps the higher score so
     * matching twice is never a penalty.
     */
    public List<SearchResultResponse> searchDocuments(String query) {
        String trimmed = query.trim();

        Map<String, SearchRepository.Scored<DocumentResponse>> best = new LinkedHashMap<>();
        Stream.concat(
                        repository.findDocumentsSemantic(
                                DocumentEmbedder.toVectorLiteral(embedder.embed(trimmed)),
                                LIMIT, maxDistance).stream(),
                        repository.findDocumentsLexical(trimmed, LIMIT).stream())
                .forEach(hit -> best.merge(
                        hit.value().id(),
                        hit,
                        (a, b) -> a.score() >= b.score() ? a : b));

        return best.values().stream()
                .sorted(Comparator.comparingDouble(SearchRepository.Scored<DocumentResponse>::score).reversed())
                .limit(LIMIT)
                .map(hit -> SearchResultResponse.ofDocument(hit.value(), round(hit.score())))
                .toList();
    }

    /** Scores are for display and ordering; full float precision is noise in the response. */
    private static double round(double score) {
        return Math.round(score * 1000.0) / 1000.0;
    }
}
