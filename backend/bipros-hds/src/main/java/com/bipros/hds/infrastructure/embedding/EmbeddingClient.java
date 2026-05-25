package com.bipros.hds.infrastructure.embedding;

import java.util.List;

public interface EmbeddingClient {
    /** Returns one float[] per input string, in the same order. dim() floats each. */
    List<float[]> embedBatch(List<String> inputs);

    int dim();
}
