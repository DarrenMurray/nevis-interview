package com.nevis.search.controllers;

import com.nevis.search.dto.SearchResultResponse;
import com.nevis.search.search.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Tag(name = "Search")
public class SearchController {

    private final SearchService searchService;

    SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * Searches across clients and their documents.
     *
     * <p>Returns an empty array rather than a 404 when nothing matches — no matches is a valid
     * result, not a missing resource.
     */
    @Operation(
            summary = "Search clients and documents",
            description = """
                    Returns a single ranked array containing both client and document hits, \
                    highest score first. Each item is tagged with a `type` so the two are \
                    distinguishable.

                    Clients match lexically — `NevisWealth` finds `john.doe@neviswealth.com`. \
                    Documents match semantically — `address proof` finds a document containing \
                    *"utility bill"*, despite sharing no words with the query.

                    **Currently stubbed: always returns an empty array.**""")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Ranked hits. An empty array means nothing matched — that is a "
                            + "valid result, so this endpoint never returns 404.",
                    content = @Content(array = @ArraySchema(
                            schema = @Schema(implementation = SearchResultResponse.class)))),
            @ApiResponse(responseCode = "400",
                    description = "`q` is absent, empty, or whitespace only.",
                    content = @Content)
    })
    @GetMapping("/search")
    public List<SearchResultResponse> search(
            @Parameter(description = "Search query. Matched against client names, emails and "
                    + "descriptions, and against document content by meaning.",
                    example = "NevisWealth", required = true)
            @RequestParam("q") @NotBlank String q) {
        return searchService.search(q);
    }

    @Operation(
            summary = "Search clients only",
            description = "Lexical match over email, name and description. `?q=NevisWealth` "
                    + "finds the client whose email is `john.doe@neviswealth.com`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Matching clients, highest score first; empty array if none.",
                    content = @Content(array = @ArraySchema(
                            schema = @Schema(implementation = SearchResultResponse.class)))),
            @ApiResponse(responseCode = "400", description = "`q` is absent or blank.",
                    content = @Content)
    })
    @GetMapping("/search/clients")
    public List<SearchResultResponse> searchClients(
            @Parameter(description = "Matched against client email, name and description.",
                    example = "NevisWealth", required = true)
            @RequestParam("q") @NotBlank String q) {
        return searchService.searchClients(q);
    }

    @Operation(
            summary = "Search documents only",
            description = "Semantic match over document content. `?q=address proof` finds a "
                    + "document containing \"utility bill\", despite sharing no words with "
                    + "the query.")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Matching documents, highest score first; empty array if none.",
                    content = @Content(array = @ArraySchema(
                            schema = @Schema(implementation = SearchResultResponse.class)))),
            @ApiResponse(responseCode = "400", description = "`q` is absent or blank.",
                    content = @Content)
    })
    @GetMapping("/search/documents")
    public List<SearchResultResponse> searchDocuments(
            @Parameter(description = "Matched against document content by meaning.",
                    example = "address proof", required = true)
            @RequestParam("q") @NotBlank String q) {
        return searchService.searchDocuments(q);
    }
}
