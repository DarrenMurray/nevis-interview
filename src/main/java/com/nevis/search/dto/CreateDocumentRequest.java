package com.nevis.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST /clients/{id}/documents}. */
@Schema(name = "CreateDocumentRequest", description = "Details of the document to attach")
public record CreateDocumentRequest(

        @Schema(description = "Human-readable document title",
                example = "Utility Bill - March 2026",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String title,

        @Schema(description = "Full document text. This is what semantic search is performed "
                + "against, so a query may match it by meaning rather than by wording.",
                example = "Thames Water. Account 8891234. Service address: 12 Acacia Avenue, "
                        + "London N1 4TG. Billing period 01-31 March 2026.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String content) {
}
