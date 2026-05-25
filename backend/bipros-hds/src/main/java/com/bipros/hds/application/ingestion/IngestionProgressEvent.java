package com.bipros.hds.application.ingestion;

import java.util.UUID;

public record IngestionProgressEvent(UUID versionId, String stage, int progressPct, String message) {}
