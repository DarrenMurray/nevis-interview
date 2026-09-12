package com.nevis.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "Client", description = "A client of the advisory practice")
public record ClientResponse(

        @Schema(description = "Server-assigned identifier",
                example = "f7231496-3fcc-474c-bf02-930aecbd37af")
        String id,

        @Schema(example = "John") String firstName,

        @Schema(example = "Doe") String lastName,

        @Schema(example = "john.doe@neviswealth.com") String email,

        @Schema(example = "Retired engineer; cautious, income-focused portfolio.")
        String description,

        @Schema(description = "Profile URLs for the client")
        List<String> socialLinks) {
}
