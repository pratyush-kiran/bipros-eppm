package com.bipros.hds.application.ingestion;

import com.bipros.hds.domain.enums.HdsChunkType;

public record PreChunk(int chunkIndex, int pageStart, int pageEnd,
                       String sectionPath, String sectionNumber,
                       HdsChunkType chunkType, String content, int contentTokens) {}
