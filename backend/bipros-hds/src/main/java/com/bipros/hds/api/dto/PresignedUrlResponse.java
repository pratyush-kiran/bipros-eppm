package com.bipros.hds.api.dto;

import java.time.Instant;

public record PresignedUrlResponse(String url, Instant expiresAt) {}
