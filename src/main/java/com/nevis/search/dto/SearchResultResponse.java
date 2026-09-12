package com.nevis.search.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One hit from {@code GET /search}.
 *
 * <p>The spec leaves search items as a bare object so that a single array can carry both kinds of
 * hit. This is a tagged union: {@code type} discriminates, and exactly one of {@code client} or
 * {@code document} is populated. The empty one is omitted from the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "SearchResult",
        description = """
                A single search hit. `type` discriminates the kind, and exactly one of `client` \
                or `document` is present — the other is omitted from the response.""")
public record SearchResultResponse(

        @Schema(description = "Which kind of entity this hit refers to",
                allowableValues = {"client", "document"}, example = "client")
        String type,

        @Schema(description = "Relevance, 0-1. Derived from trigram similarity for clients and "
                + "from vector distance for documents, then normalised so the two are comparable.",
                minimum = "0", maximum = "1", example = "0.91")
        double score,

        @Schema(description = "Populated when `type` is `client`")
        ClientResponse client,

        @Schema(description = "Populated when `type` is `document`")
        DocumentResponse document) {

    public static SearchResultResponse ofClient(ClientResponse client, double score) {
        return new SearchResultResponse("client", score, client, null);
    }

    public static SearchResultResponse ofDocument(DocumentResponse document, double score) {
        return new SearchResultResponse("document", score, null, document);
    }
}
