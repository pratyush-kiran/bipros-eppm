package com.bipros.hds.api.dto;

import com.bipros.hds.domain.enums.HdsChunkType;

import java.util.UUID;

public record HdsChunkResponse(UUID id, UUID versionId, String versionLabel,
                               int pageStart, int pageEnd, String sectionPath,
                               HdsChunkType chunkType, String content) {}
