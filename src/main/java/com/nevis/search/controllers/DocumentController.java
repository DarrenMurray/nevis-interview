package com.nevis.search.controllers;

import com.nevis.search.dto.CreateDocumentRequest;
import com.nevis.search.dto.DocumentResponse;
import com.nevis.search.search.DocumentEmbedder;
import com.nevis.search.store.ClientStore;
import com.nevis.search.store.DocumentStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/clients/{clientId}/documents")
@Tag(name = "Documents")
public class DocumentController {

    private final ClientStore clients;
    private final DocumentStore documents;
    private final DocumentEmbedder embedder;

    DocumentController(ClientStore clients, DocumentStore documents, DocumentEmbedder embedder) {
        this.clients = clients;
        this.documents = documents;
        this.embedder = embedder;
    }

    @Operation(
            summary = "Attach a document to a client",
            description = "Stores a document against a client. Its content is indexed for "
                    + "semantic search, so it can later be found by meaning rather than by "
                    + "matching words.")
    @ApiResponses({
            @ApiResponse(responseCode = "201",
                    description = "Created. The `Location` header holds the new document's URI.",
                    content = @Content(schema = @Schema(implementation = DocumentResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "`title` or `content` is missing or blank.",
                    content = @Content),
            @ApiResponse(responseCode = "404",
                    description = "No client exists with this id.",
                    content = @Content)
    })
    @PostMapping
    public ResponseEntity<DocumentResponse> create(
            @Parameter(description = "Identifier of the owning client",
                    example = "f7231496-3fcc-474c-bf02-930aecbd37af")
            @PathVariable String clientId,
            @Valid @RequestBody CreateDocumentRequest request) {
        if (!clients.exists(clientId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such client");
        }

        DocumentResponse body = documents.insert(clientId, request);

        // Embedded synchronously so the document is searchable as soon as this returns.
        // Deferring it would make a create-then-search sequence non-deterministic.
        String summary = embedder.embed(body);
        body = new DocumentResponse(
                body.id(), body.clientId(), body.title(), summary, body.content(), body.createdAt());

        return ResponseEntity
                .created(URI.create("/clients/" + clientId + "/documents/" + body.id()))
                .body(body);
    }
}
