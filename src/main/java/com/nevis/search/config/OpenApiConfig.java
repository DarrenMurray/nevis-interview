package com.nevis.search.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.core.jackson.ModelResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document metadata.
 *
 * <p>The document itself is assembled by springdoc from the annotations on the controllers and
 * DTOs — this class only supplies the parts that have nowhere else to live. Served as JSON at
 * {@code /v3/api-docs} and as Swagger UI at {@code /swagger-ui.html}.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Nevis Search API",
                version = "0.0.1",
                description = """
                        Search across clients and their documents for a WealthTech advisor platform.

                        Two query styles are supported, because they need different machinery:

                        * **Clients — lexical.** `?q=NevisWealth` matches the client whose email is \
                        `john.doe@neviswealth.com`, by case-insensitive substring.
                        * **Documents — semantic.** `?q=address proof` matches a document containing \
                        *"utility bill"*. Those phrases share no characters, so this is vector \
                        similarity over embeddings, not keyword matching.

                        **Current state: skeleton.** Endpoints validate their input and return \
                        correctly-shaped responses, but nothing is persisted and `/search` always \
                        returns an empty array.
                        """,
                contact = @Contact(name = "Darren Murray")),
        servers = @Server(url = "http://localhost:8080", description = "Local development"),
        tags = {
                @Tag(name = "Clients", description = "Create and manage advisor clients"),
                @Tag(name = "Documents", description = "Documents belonging to a client"),
                @Tag(name = "Search", description = "Unified search across clients and documents")
        })
public class OpenApiConfig {

    /**
     * Makes the generated schemas use snake_case, matching what the API actually serves.
     *
     * <p>Without this the document advertises {@code firstName} while the endpoints return
     * {@code first_name}. The cause is a Jackson version split: Spring Boot 4 serialises with
     * Jackson 3 ({@code tools.jackson}), but swagger-core still builds schemas with its own
     * Jackson 2 {@code ObjectMapper}, which cannot see
     * {@code spring.jackson.property-naming-strategy}. Supplying the resolver with a mapper
     * configured the same way closes the gap.
     *
     * <p>This must stay in step with {@code spring.jackson.property-naming-strategy} in
     * {@code application.yaml}. {@code OpenApiContractTest} fails if the two ever diverge.
     */
    @Bean
    ModelResolver snakeCaseModelResolver() {
        ObjectMapper schemaMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return new ModelResolver(schemaMapper);
    }
}
