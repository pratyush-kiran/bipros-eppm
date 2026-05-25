package com.bipros.hds.application.retrieval;

import java.util.UUID;

public record Citation(String marker, UUID chunkId, UUID versionId,
                       String versionLabel, String sectionPath,
                       int pageStart, int pageEnd, String excerpt) {}
