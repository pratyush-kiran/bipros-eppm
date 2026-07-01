package com.bipros.api.dto;

import lombok.Data;

import java.util.UUID;

/** Payload for the EPS-node code data-correction endpoint. */
@Data
public class EpsCodeCorrectionRequest {

    /** The EPS node whose code is being corrected. */
    private UUID epsNodeId;

    /** The new code to set — must be non-blank, ≤20 chars, and unique. */
    private String code;
}
