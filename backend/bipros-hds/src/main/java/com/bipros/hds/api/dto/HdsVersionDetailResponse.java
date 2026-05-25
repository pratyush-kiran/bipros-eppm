package com.bipros.hds.api.dto;

import com.bipros.hds.domain.HdsVersion;

public record HdsVersionDetailResponse(HdsVersionResponse version, String indexingError) {
    public static HdsVersionDetailResponse from(HdsVersion v) {
        return new HdsVersionDetailResponse(HdsVersionResponse.from(v), v.getIndexingError());
    }
}
