package com.nevis.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** Request body for {@code POST /clients}. JSON is snake_case: {@code firstName} binds from {@code first_name}. */
@Schema(name = "CreateClientRequest", description = "Details of the client to create")
public record CreateClientRequest(

        @Schema(description = "Client's given name", example = "John",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String firstName,

        @Schema(description = "Client's family name", example = "Doe",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String lastName,

        @Schema(description = "Contact email. Searchable, including the domain.",
                example = "john.doe@neviswealth.com",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Email String email,

        @Schema(description = "Free-text notes about the client. Searchable.",
                example = "Retired engineer; cautious, income-focused portfolio.")
        String description,

        @Schema(description = "Profile URLs for the client")
        List<String> socialLinks) {
}
