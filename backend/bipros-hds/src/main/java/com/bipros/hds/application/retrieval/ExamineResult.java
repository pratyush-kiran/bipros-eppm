package com.bipros.hds.application.retrieval;

import java.util.List;

public record ExamineResult(boolean sufficient, List<String> followUpQueries) {}
