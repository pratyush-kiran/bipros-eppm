package com.bipros.hds.application.retrieval;

import java.util.List;
import java.util.Map;

public record RetrievalAnswer(String answer, List<Citation> citations,
                              VerifyResult verifier, Map<String, Object> metadata) {}
