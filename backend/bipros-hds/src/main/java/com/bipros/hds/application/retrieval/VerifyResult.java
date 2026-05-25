package com.bipros.hds.application.retrieval;

import java.util.List;

public record VerifyResult(boolean passed, List<Issue> issues) {
    public record Issue(String claim, String citation, String explanation) {}
}
