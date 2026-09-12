package com.nevis.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(name = "Document", description = "A document belonging to a client")
public record DocumentResponse(

        @Schema(description = "Server-assigned identifier",
                example = "17a32d53-eb66-4364-a8aa-f690b8e79868")
        String id,

        @Schema(description = "Identifier of the owning client",
                example = "f7231496-3fcc-474c-bf02-930aecbd37af")
        String clientId,

        @Schema(example = "Utility Bill - March 2026") String title,

        @Schema(example = "Thames Water. Account 8891234. Service address: 12 Acacia Avenue, "
                + "London N1 4TG. Billing period 01-31 March 2026.")
        String content,

        @Schema(description = "When the document was stored",
                example = "2026-09-12T14:20:35.754252647+01:00")
        OffsetDateTime createdAt) {
}
