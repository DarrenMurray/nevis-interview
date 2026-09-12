package com.nevis.search.search;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Extractive summaries: picks the sentences most representative of a text rather than
 * generating new prose.
 *
 * <p>Reuses the embedding model that is already loaded, so a summary costs no extra dependency,
 * no external API and no inference beyond the embeddings themselves. Every sentence returned is
 * one the document actually contains, which means a summary cannot invent a fact — the common
 * failure mode of a generated one.
 */
@Component
public class Summariser {

    private final DocumentEmbedder embedder;

    Summariser(DocumentEmbedder embedder) {
        this.embedder = embedder;
    }

    /**
     * Summarises one document by choosing the passages closest to its own centroid.
     *
     * <p>The centroid is the average of the passage vectors — roughly "what this document is
     * about". Passages nearest it are the most central, and outliers (a stray account number, a
     * closing formality) fall away.
     */
    public String summarise(String text, int sentences) {
        List<String> passages = DocumentEmbedder.chunk(text);
        if (passages.isEmpty()) {
            return null;
        }
        if (passages.size() <= sentences) {
            return String.join(" ", passages);
        }

        List<float[]> vectors = passages.stream().map(embedder::embed).toList();
        float[] centroid = centroid(vectors);

        // Rank by closeness to the centroid, keep the best few, then restore document order so
        // the summary still reads as prose rather than as a ranked list.
        List<Integer> chosen = java.util.stream.IntStream.range(0, passages.size())
                .boxed()
                .sorted(Comparator.comparingDouble(i -> -cosine(vectors.get(i), centroid)))
                .limit(sentences)
                .sorted()
                .toList();

        return chosen.stream().map(passages::get).reduce((a, b) -> a + " " + b).orElse(null);
    }


    private static float[] centroid(List<float[]> vectors) {
        float[] mean = new float[vectors.getFirst().length];
        for (float[] vector : vectors) {
            for (int i = 0; i < mean.length; i++) {
                mean[i] += vector[i];
            }
        }
        for (int i = 0; i < mean.length; i++) {
            mean[i] /= vectors.size();
        }
        return mean;
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        return denominator == 0 ? 0 : dot / denominator;
    }
}
