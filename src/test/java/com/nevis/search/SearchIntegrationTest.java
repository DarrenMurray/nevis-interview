package com.nevis.search;

import com.nevis.search.dto.SearchResultResponse;
import com.nevis.search.search.DocumentEmbedder;
import com.nevis.search.search.SearchService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Search against a real Postgres, seeded by the same migrations production runs.
 *
 * <p>Requires Docker. The image is pgvector's rather than plain postgres because V1 creates the
 * vector extension.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("integration")
class SearchIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired
    SearchService search;

    @Autowired
    DocumentEmbedder embedder;

    @BeforeAll
    static void ensureDockerAvailable() {
        // Fails clearly rather than as a cryptic container-fetch error.
        assertThat(postgres.isRunning()).isTrue();
    }

    @BeforeEach
    void embedSeededDocuments() {
        // @SpringBootTest never publishes ApplicationReadyEvent, so the backfill that runs in
        // the real app does not fire here. Without this the semantic assertions below would
        // pass for the wrong reason: no vectors means no hits.
        embedder.backfill();
    }

    private static List<String> emails(List<SearchResultResponse> results) {
        return results.stream().map(r -> r.client().email()).toList();
    }

    private static List<String> titles(List<SearchResultResponse> results) {
        return results.stream().map(r -> r.document().title()).toList();
    }

    @Test
    @DisplayName("?q=NevisWealth finds the client by their email domain")
    void clientMatchesOnEmailDomain() {
        List<SearchResultResponse> results = search.searchClients("NevisWealth");

        assertThat(emails(results)).contains("john.doe@neviswealth.com");
        assertThat(results).allSatisfy(r -> assertThat(r.type()).isEqualTo("client"));
        assertThat(results.getFirst().score()).isGreaterThan(0.9);
    }

    @Test
    @DisplayName("client search is case-insensitive and matches descriptions")
    void clientMatchesCaseInsensitivelyAndOnDescription() {
        assertThat(emails(search.searchClients("okonkwo")))
                .contains("beatrice.okonkwo@harbourtrust.com");
        // "pension" appears only in Beatrice's description, not her name or email.
        assertThat(emails(search.searchClients("pension")))
                .contains("beatrice.okonkwo@harbourtrust.com");
    }

    @Test
    @DisplayName("LIKE wildcards in the query are escaped, not honoured")
    void wildcardsAreEscaped() {
        // Unescaped, '%' would match every row.
        assertThat(search.searchClients("%")).isEmpty();
        assertThat(search.searchClients("_")).isEmpty();
        assertThat(search.searchClients("%wealth")).isEmpty();
    }

    @Test
    @DisplayName("no matches yields an empty list, not an error")
    void noMatches() {
        assertThat(search.searchClients("zzzzzznomatch")).isEmpty();
        assertThat(search.searchDocuments("zzzzzznomatch")).isEmpty();
    }

    @Test
    @DisplayName("document search finds exact terms, which embeddings are weak at")
    void documentMatchesExactTerms() {
        assertThat(titles(search.searchDocuments("utility bill")))
                .contains("Utility Bill - March 2026");
        // An account number: the case lexical search exists for.
        assertThat(titles(search.searchDocuments("8891234")))
                .contains("Utility Bill - March 2026");
    }

    @Test
    @DisplayName("'address proof' returns the utility bill, which shares no words with it")
    void semanticSearchBridgesUnrelatedWording() {
        List<SearchResultResponse> results = search.searchDocuments("address proof");

        // The phrase appears nowhere in the seed data, so a lexical match is impossible and
        // this can only succeed through embedding similarity.
        assertThat(titles(results)).contains("Utility Bill - March 2026");
        assertThat(titles(results).getFirst()).isEqualTo("Utility Bill - March 2026");
    }

    @Test
    @DisplayName("semantic search rejects nonsense rather than ranking the whole table")
    void semanticSearchHasAFloor() {
        // Every vector has some distance to every other, so without a distance cutoff an
        // unrelated query would return all 13 documents in arbitrary order.
        assertThat(search.searchDocuments("zebra unicorn nonsense")).isEmpty();
    }

    @Test
    @DisplayName("documents are embedded per passage, not once per document")
    void documentsAreChunked() {
        // A utility bill's content is mostly account numbers; the sentence establishing
        // residence only ranks if passages are embedded separately.
        assertThat(search.searchDocuments("who lives at the property")).isNotEmpty();
    }

    @Test
    @DisplayName("unified search returns both kinds, ranked together")
    void unifiedSearchMixesTypes() {
        List<SearchResultResponse> results = search.search("pension");

        assertThat(results.stream().map(SearchResultResponse::type).distinct())
                .containsExactlyInAnyOrder("client", "document");
        assertThat(results).isSortedAccordingTo(
                (a, b) -> Double.compare(b.score(), a.score()));
    }
}
