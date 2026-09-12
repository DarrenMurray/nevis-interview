package com.nevis.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the OpenAPI document against drifting from what the API actually serves.
 *
 * <p>This is not a formality. Spring Boot 4 serialises with Jackson 3, but swagger-core builds
 * schemas with its own bundled Jackson 2 and so cannot see
 * {@code spring.jackson.property-naming-strategy}. Left alone, the document advertises
 * {@code firstName} while the endpoints return {@code first_name} — documentation that is worse
 * than none, because it looks authoritative. {@code OpenApiConfig#snakeCaseModelResolver} corrects
 * it; these tests fail if that bean is removed or the two naming settings diverge.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiContractTest {

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private JsonNode postJson(String path, String body) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readTree(response.body());
    }

    private static Set<String> fieldNames(JsonNode node) {
        return new TreeSet<>(node.propertyNames());
    }

    private Set<String> schemaProperties(String schema) throws Exception {
        return fieldNames(get("/v3/api-docs").at("/components/schemas/" + schema + "/properties"));
    }

    @Test
    @DisplayName("document is OpenAPI 3.1 and describes every endpoint in the assignment spec")
    void documentCoversTheSpec() throws Exception {
        JsonNode doc = get("/v3/api-docs");

        assertThat(doc.get("openapi").asString()).startsWith("3.1");
        assertThat(fieldNames(doc.get("paths")))
                .containsExactlyInAnyOrder("/clients", "/clients/{clientId}/documents", "/search");
        assertThat(doc.at("/paths/~1clients/post/responses").propertyNames())
                .contains("201", "400");
        assertThat(doc.at("/paths/~1search/get/responses").propertyNames())
                .contains("200", "400");
    }

    @Test
    @DisplayName("no schema property is camelCase")
    void schemaPropertiesAreSnakeCase() throws Exception {
        JsonNode schemas = get("/v3/api-docs").at("/components/schemas");

        for (String schema : fieldNames(schemas)) {
            for (String property : fieldNames(schemas.at("/" + schema + "/properties"))) {
                assertThat(property)
                        .as("property '%s' of schema '%s' should be snake_case", property, schema)
                        .doesNotMatch(".*[A-Z].*");
            }
        }
    }

    @Test
    @DisplayName("documented Client fields are exactly the fields the API returns")
    void clientSchemaMatchesTheWireFormat() throws Exception {
        // Every optional field is populated: null fields are dropped from the response by
        // default-property-inclusion=non_null, which would make the comparison meaningless.
        JsonNode served = postJson("/clients", """
                {
                  "first_name": "John",
                  "last_name": "Doe",
                  "email": "john.doe@neviswealth.com",
                  "description": "Retired engineer.",
                  "social_links": ["https://www.linkedin.com/in/johndoe"]
                }""");

        assertThat(fieldNames(served)).isEqualTo(schemaProperties("Client"));
    }

    @Test
    @DisplayName("documented Document fields are exactly the fields the API returns")
    void documentSchemaMatchesTheWireFormat() throws Exception {
        JsonNode served = postJson("/clients/abc-123/documents", """
                {
                  "title": "Utility Bill - March 2026",
                  "content": "Thames Water. Service address: 12 Acacia Avenue, London N1 4TG."
                }""");

        assertThat(fieldNames(served)).isEqualTo(schemaProperties("Document"));
    }

    @Test
    @DisplayName("required fields are advertised as required")
    void requiredFieldsAreDocumented() throws Exception {
        JsonNode doc = get("/v3/api-docs");

        assertThat(doc.at("/components/schemas/CreateClientRequest/required").valueStream()
                .map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("first_name", "last_name", "email");
        assertThat(doc.at("/components/schemas/CreateDocumentRequest/required").valueStream()
                .map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("title", "content");
    }

    @Test
    @DisplayName("Swagger UI is served")
    void swaggerUiIsAvailable() throws Exception {
        HttpResponse<Void> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/swagger-ui/index.html"))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
    }
}
