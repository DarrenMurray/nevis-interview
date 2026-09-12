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

/** OpenAPI metadata. The document is built by springdoc from controller and DTO annotations. */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Nevis Search API",
                version = "0.0.1",
                description = """
                        Search across clients and their documents for a WealthTech advisor platform.

                        Two query styles are supported, because they need different machinery:

                        * **Clients - lexical.** `?q=NevisWealth` matches the client whose email is \
                        `john.doe@neviswealth.com`, by case-insensitive substring.
                        * **Documents - semantic.** `?q=address proof` matches a document containing \
                        *"utility bill"*. Those phrases share no characters, so this is vector \
                        similarity over embeddings, not keyword matching.

                        **Current state:** endpoints validate input and return correctly-shaped \
                        responses; search is not implemented and `/search` returns an empty array.
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
     * Generates snake_case schema names.
     *
     * <p>Boot 4 serialises with Jackson 3, but swagger-core builds schemas with its own Jackson 2
     * mapper and cannot see {@code spring.jackson.property-naming-strategy} - without this the
     * document says {@code firstName} while the API returns {@code first_name}.
     *
     * <p>Must match {@code application.yaml}; {@code OpenApiContractTest} fails if they diverge.
     */
    @Bean
    ModelResolver snakeCaseModelResolver() {
        ObjectMapper schemaMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return new ModelResolver(schemaMapper);
    }
}
