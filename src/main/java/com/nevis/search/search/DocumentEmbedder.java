package com.nevis.search.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * Turns document text into vectors, and backfills any document that has none.
 *
 * <p>The model runs in-process (ONNX Runtime via DJL), so embedding costs no network call and
 * needs no API key.
 */
@Component
public class DocumentEmbedder {

    private static final Logger log = LoggerFactory.getLogger(DocumentEmbedder.class);

    /** Split after . ! or ? followed by whitespace. */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");

    /** Roughly two or three sentences - enough context without diluting the passage. */
    private static final int MAX_CHUNK_CHARS = 260;

    /** Sentences kept in a per-document summary. */
    private static final int SUMMARY_SENTENCES = 2;

    private final EmbeddingModel model;
    private final SearchRepository repository;
    private final boolean backfillEnabled;
    private final ObjectProvider<Summariser> summariser;

    DocumentEmbedder(EmbeddingModel model,
                     SearchRepository repository,
                     @Value("${search.embedding.backfill:true}") boolean backfillEnabled,
                     // Lazy: Summariser depends on this class, so injecting it directly would
                     // be a constructor cycle.
                     ObjectProvider<Summariser> summariser) {
        this.model = model;
        this.repository = repository;
        this.backfillEnabled = backfillEnabled;
        this.summariser = summariser;
    }

    /** Embeds text for storage or for querying. Both sides must use the same model. */
    public float[] embed(String text) {
        return model.embed(text);
    }

    /**
     * Splits text into passages for embedding.
     *
     * <p>Sentence-boundary split, then short sentences are joined so a passage carries enough
     * context to mean something. Overlapping is unnecessary at this size and would double the
     * row count.
     */
    static List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String sentence : SENTENCE_END.split(text.strip())) {
            String trimmed = sentence.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (current.length() + trimmed.length() > MAX_CHUNK_CHARS && !current.isEmpty()) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(trimmed);
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    /** pgvector's text form: {@code [0.1,0.2,...]}. */
    public static String toVectorLiteral(float[] vector) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (float value : vector) {
            joiner.add(Float.toString(value));
        }
        return joiner.toString();
    }

    /**
     * Embeds one document and its passages, and returns the summary generated for it.
     *
     * <p>The caller needs the summary back because the row is inserted before it exists, so the
     * response built from that insert would otherwise omit the field the schema promises.
     */
    public String embed(com.nevis.search.dto.DocumentResponse document) {
        return embedOne(document.id(), document.title(), document.content());
    }

    private String embedOne(String id, String title, String content) {
        repository.updateEmbedding(id, toVectorLiteral(embed(title + "\n" + content)));

        List<String> passages = new ArrayList<>();
        passages.add(title);
        passages.addAll(chunk(content));

        repository.replaceChunks(id, passages.stream()
                .map(passage -> new SearchRepository.Chunk(passage, toVectorLiteral(embed(passage))))
                .toList());

        // Query-independent, so it is computed once on write rather than per search.
        String summary = summariser.getObject().summarise(content, SUMMARY_SENTENCES);
        repository.updateSummary(id, summary);
        return summary;
    }

    /**
     * Embeds documents stored without a vector.
     *
     * <p>Runs after the server is accepting traffic rather than during startup, so a slow
     * backfill cannot fail the container's health check. Seeded documents arrive without
     * embeddings, so without this the semantic path would have nothing to search.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        // Checked here rather than with @ConditionalOnProperty: that annotation applies to
        // bean definitions, not to @EventListener methods, where it is silently ignored.
        if (!backfillEnabled) {
            return;
        }

        var pending = repository.findUnembedded(500);
        if (pending.isEmpty()) {
            return;
        }

        log.info("Embedding {} document(s) with no vector", pending.size());
        int done = 0;
        for (var document : pending) {
            try {
                embedOne(document.id(), document.title(), document.content());
                done++;
            } catch (RuntimeException e) {
                // One bad document should not stop the rest from being searchable.
                log.warn("Could not embed document {}: {}", document.id(), e.getMessage());
            }
        }
        log.info("Embedded {}/{} document(s)", done, pending.size());
    }
}
