package com.nevis.search.controllers;

import com.nevis.search.dto.ClientResponse;
import com.nevis.search.dto.CreateClientRequest;
import com.nevis.search.store.ClientStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/clients")
@Tag(name = "Clients")
public class ClientController {

    private final ClientStore clients;

    ClientController(ClientStore clients) {
        this.clients = clients;
    }

    @Operation(
            summary = "Create a client",
            description = "Registers a client. The email, name and description all become "
                    + "searchable via `GET /search`.")
    @ApiResponses({
            @ApiResponse(responseCode = "201",
                    description = "Created. The `Location` header holds the new client's URI.",
                    content = @Content(schema = @Schema(implementation = ClientResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "A required field is missing, or the email is malformed.",
                    content = @Content),
            @ApiResponse(responseCode = "409",
                    description = "A client with this email already exists.",
                    content = @Content)
    })
    @PostMapping
    public ResponseEntity<ClientResponse> create(@Valid @RequestBody CreateClientRequest request) {
        ClientResponse body;
        try {
            body = clients.insert(request);
        } catch (DuplicateKeyException e) {
            // email is UNIQUE and citext, so this also catches a differently-cased duplicate.
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "A client with that email already exists");
        }
        return ResponseEntity.created(URI.create("/clients/" + body.id())).body(body);
    }
}
