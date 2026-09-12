package com.nevis.search.controllers;

import com.nevis.search.dto.ClientResponse;
import com.nevis.search.dto.CreateClientRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/clients")
@Tag(name = "Clients")
public class ClientController {

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
                    description = "Not yet implemented — a client with this email already exists.",
                    content = @Content)
    })
    @PostMapping
    public ResponseEntity<ClientResponse> create(@Valid @RequestBody CreateClientRequest request) {
        // TODO: persist. Stubbed: echoes the request with a generated id.
        String id = UUID.randomUUID().toString();
        ClientResponse body = new ClientResponse(
                id,
                request.firstName(),
                request.lastName(),
                request.email(),
                request.description(),
                request.socialLinks() == null ? List.of() : request.socialLinks());
        return ResponseEntity.created(URI.create("/clients/" + id)).body(body);
    }
}
