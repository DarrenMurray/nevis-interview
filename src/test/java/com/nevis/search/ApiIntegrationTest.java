package com.nevis.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The write endpoints, over real HTTP against real Postgres. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("integration")
class ApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode get(String path) throws Exception {
        return json.readTree(http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString()).body());
    }

    private static String uniqueEmail() {
        return "created-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    @DisplayName("a created client is persisted and immediately searchable")
    void createdClientIsSearchable() throws Exception {
        String email = uniqueEmail();
        HttpResponse<String> created = post("/clients", """
                {"first_name":"Wilhelmina","last_name":"Ashcombe","email":"%s",
                 "description":"Vineyard owner in Kent"}
                """.formatted(email));

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("Location")).isPresent();

        JsonNode body = json.readTree(created.body());
        assertThat(body.get("id").asString()).isNotBlank();
        assertThat(body.get("email").asString()).isEqualTo(email);

        // The point of persisting: it comes back from a subsequent query.
        assertThat(get("/search/clients?q=Ashcombe").toString()).contains("Ashcombe");
    }

    @Test
    @DisplayName("a duplicate email is rejected with 409")
    void duplicateEmailConflicts() throws Exception {
        String email = uniqueEmail();
        String body = """
                {"first_name":"First","last_name":"Attempt","email":"%s"}
                """.formatted(email);

        assertThat(post("/clients", body).statusCode()).isEqualTo(201);
        assertThat(post("/clients", body).statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("email uniqueness ignores case, because the column is citext")
    void duplicateEmailIsCaseInsensitive() throws Exception {
        String email = uniqueEmail();
        assertThat(post("/clients", """
                {"first_name":"Lower","last_name":"Case","email":"%s"}
                """.formatted(email)).statusCode()).isEqualTo(201);

        assertThat(post("/clients", """
                {"first_name":"Upper","last_name":"Case","email":"%s"}
                """.formatted(email.toUpperCase())).statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("a document for an unknown client is 404, not a dangling row")
    void documentForUnknownClientIsNotFound() throws Exception {
        HttpResponse<String> response = post(
                "/clients/" + UUID.randomUUID() + "/documents",
                """
                {"title":"Orphan","content":"Should never be stored"}
                """);

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("a created document is embedded on write and found semantically")
    void createdDocumentIsSemanticallySearchable() throws Exception {
        String clientId = json.readTree(post("/clients", """
                {"first_name":"Marisol","last_name":"Quintero","email":"%s"}
                """.formatted(uniqueEmail())).body()).get("id").asString();

        // Deliberately shares no word with the query below — not "utility", not "bill" — so a
        // match can only come from the embedding.
        assertThat(post("/clients/" + clientId + "/documents", """
                {"title":"Scottish Power Statement",
                 "content":"Electricity supply for the dwelling at 4 Rowan Close. This statement confirms the person residing there during the quarter."}
                """).statusCode()).isEqualTo(201);

        // Embedding happens during the POST, so no retry loop is needed here.
        assertThat(get("/search/documents?q=utility%20bill").toString())
                .contains("Scottish Power Statement");
    }

    @Test
    @DisplayName("documented Client fields are exactly the fields the API returns")
    void clientSchemaMatchesTheWireFormat() throws Exception {
        // Every optional field populated: non_null inclusion drops nulls, which would make
        // the comparison vacuous.
        JsonNode served = json.readTree(post("/clients", """
                {"first_name":"Schema","last_name":"Check","email":"%s",
                 "description":"Populated so nothing is dropped",
                 "social_links":["https://example.com/profile"]}
                """.formatted(uniqueEmail())).body());

        JsonNode documented = get("/v3/api-docs").at("/components/schemas/Client/properties");
        assertThat(served.propertyNames()).containsExactlyInAnyOrderElementsOf(
                documented.propertyNames());
    }

    @Test
    @DisplayName("documented Document fields are exactly the fields the API returns")
    void documentSchemaMatchesTheWireFormat() throws Exception {
        String clientId = json.readTree(post("/clients", """
                {"first_name":"Doc","last_name":"Owner","email":"%s"}
                """.formatted(uniqueEmail())).body()).get("id").asString();

        JsonNode served = json.readTree(post("/clients/" + clientId + "/documents", """
                {"title":"Schema Check","content":"Body text for the schema comparison."}
                """).body());

        JsonNode documented = get("/v3/api-docs").at("/components/schemas/Document/properties");
        assertThat(served.propertyNames()).containsExactlyInAnyOrderElementsOf(
                documented.propertyNames());
    }

    @Test
    @DisplayName("validation still rejects bad input before touching the database")
    void validationRejectsBadInput() throws Exception {
        assertThat(post("/clients", """
                {"first_name":"No","last_name":"Email","email":"not-an-email"}
                """).statusCode()).isEqualTo(400);
    }
}
