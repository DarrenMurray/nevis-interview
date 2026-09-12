package com.nevis.search.search;

import com.nevis.search.dto.DocumentResponse;
import com.nevis.search.dto.SearchResultResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    /** Sentences in the overall summary of a result set. */
    private static final int OVERVIEW_SENTENCES = 2;

    /** Documents considered when summarising a result set; the tail adds noise, not signal. */
    private static final int OVERVIEW_DOCUMENTS = 5;

    private final SearchRepository repository;
    private final DocumentEmbedder embedder;
    private final Summariser summariser;
    private final double maxDistance;

    SearchService(SearchRepository repository,
                  DocumentEmbedder embedder,
                  Summariser summariser,
                  @Value("${search.semantic.max-distance:0.75}") double maxDistance) {
        this.repository = repository;
        this.embedder = embedder;
        this.summariser = summariser;
        this.maxDistance = maxDistance;
    }

    /**
     * A factual description of a result set: how many, for what, and which documents.
     *
     * <p>Deliberately not an extracted sentence. Summarising the documents' own text produced
     * lines like "the account holder has held this account since 2019", which is true of one
     * document and misleading as a description of results spanning several clients. A meta
     * summary cannot misattribute because it says nothing about any individual document.
     */
    public String summariseResults(String query, List<SearchResultResponse> results) {
        List<DocumentResponse> documents = results.stream()
                .map(SearchResultResponse::document)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (documents.isEmpty()) {
            return null;
        }

        List<String> titles = documents.stream()
                .limit(OVERVIEW_DOCUMENTS)
                .map(DocumentResponse::title)
                .toList();

        // Whether anything actually contains the phrase decides how the match is described;
        // claiming a semantic match when it was a plain text hit would be wrong.
        String needle = query.toLowerCase(java.util.Locale.ROOT);
        boolean literal = documents.stream().anyMatch(d ->
                (d.title() + " " + d.content()).toLowerCase(java.util.Locale.ROOT).contains(needle));

        return "%d document%s match \"%s\"%s: %s. %s".formatted(
                documents.size(),
                documents.size() == 1 ? "" : "s",
                query,
                documents.size() > titles.size() ? ", top " + titles.size() : "",
                join(titles),
                literal
                        ? "Matched on wording and on meaning."
                        : "None contains that phrase; they were matched on meaning alone.");
    }

    /** "a, b and c" - an Oxford comma would read oddly in a one-line summary. */
    private static String join(List<String> items) {
        if (items.size() == 1) {
            return items.getFirst();
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.getLast();
    }

    /** Clients and documents together, ranked into one list. */
    public List<SearchResultResponse> search(String query) {
        return Stream.concat(searchClients(query).stream(), searchDocuments(query).stream())
                .sorted(Comparator.comparingDouble(SearchResultResponse::score).reversed())
                .toList();
    }

    public List<SearchResultResponse> searchClients(String query) {
        List<SearchResultResponse> results = repository.findClients(query.trim(), LIMIT).stream()
                .map(hit -> SearchResultResponse.ofClient(hit.value(), round(hit.score())))
                .toList();
        logSearch("clients", results.size());
        return results;
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

        List<SearchResultResponse> results = best.values().stream()
                .sorted(Comparator.comparingDouble(SearchRepository.Scored<DocumentResponse>::score).reversed())
                .limit(LIMIT)
                .map(hit -> SearchResultResponse.ofDocument(hit.value(), round(hit.score())))
                .toList();
        logSearch("documents", results.size());
        return results;
    }

    /**
     * Result counts alongside the request context the filter already recorded, so "which searches
     * return nothing" is answerable from the logs without adding analytics.
     */
    private static void logSearch(String kind, int hits) {
        MDC.put("search_kind", kind);
        MDC.put("result_count", String.valueOf(hits));
        log.info("Search executed");
    }

    /** Scores are for display and ordering; full float precision is noise in the response. */
    private static double round(double score) {
        return Math.round(score * 1000.0) / 1000.0;
    }
}
