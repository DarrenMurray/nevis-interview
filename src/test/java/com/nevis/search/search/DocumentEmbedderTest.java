package com.nevis.search.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Chunking is pure text handling, so it needs no database or model. */
class DocumentEmbedderTest {

    @Test
    @DisplayName("splits on sentence boundaries")
    void splitsSentences() {
        List<String> chunks = DocumentEmbedder.chunk(
                "First sentence. Second sentence! Third one? Fourth.");

        assertThat(chunks).isNotEmpty();
        assertThat(String.join(" ", chunks)).contains("First sentence", "Fourth");
    }

    @Test
    @DisplayName("groups short sentences so a passage carries context")
    void groupsShortSentences() {
        // Individually these are too short to embed meaningfully.
        List<String> chunks = DocumentEmbedder.chunk("A. B. C. D.");

        assertThat(chunks).hasSize(1);
    }

    @Test
    @DisplayName("splits text longer than the passage limit")
    void splitsLongText() {
        String sentence = "This sentence is long enough to matter on its own. ";
        List<String> chunks = DocumentEmbedder.chunk(sentence.repeat(8));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk).isNotBlank());
    }

    @Test
    @DisplayName("handles empty and whitespace-only input without producing empty passages")
    void handlesEmptyInput() {
        assertThat(DocumentEmbedder.chunk("")).isEmpty();
        assertThat(DocumentEmbedder.chunk("   \n  ")).isEmpty();
    }

    @Test
    @DisplayName("renders vectors in pgvector's literal form")
    void rendersVectorLiteral() {
        assertThat(DocumentEmbedder.toVectorLiteral(new float[]{1.5f, -0.25f}))
                .isEqualTo("[1.5,-0.25]");
    }
}
