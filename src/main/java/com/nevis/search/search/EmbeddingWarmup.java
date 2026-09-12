package com.nevis.search.search;

import org.springframework.ai.transformers.TransformersEmbeddingModel;

/**
 * Loads the embedding model once, at image build time.
 *
 * <p>Not part of the running application. DJL fetches native libraries on first use and caches
 * them under {@code DJL_CACHE_DIR}; running this during the build populates that cache so the
 * container never downloads anything. Without it the first embed pulls ~200MB of natives — on
 * Cloud Run with min-instances 0, that is every cold start.
 */
public final class EmbeddingWarmup {

    private EmbeddingWarmup() {
    }

    public static void main(String[] args) throws Exception {
        TransformersEmbeddingModel model = new TransformersEmbeddingModel();
        String modelUri = System.getenv("SPRING_AI_EMBEDDING_TRANSFORMER_ONNX_MODEL_URI");
        String tokenizerUri = System.getenv("SPRING_AI_EMBEDDING_TRANSFORMER_TOKENIZER_URI");
        if (modelUri != null) {
            model.setModelResource(modelUri);
        }
        if (tokenizerUri != null) {
            model.setTokenizerResource(tokenizerUri);
        }
        model.afterPropertiesSet();

        float[] vector = model.embed("warm up the tokenizer and the model");
        if (vector.length == 0) {
            throw new IllegalStateException("warm-up produced an empty embedding");
        }
        System.out.println("Warm-up embedded " + vector.length + " dimensions");
    }
}
