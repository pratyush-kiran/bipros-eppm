package com.bipros.api.dto;

import java.util.UUID;

/**
 * Result of an EPS-node code data-correction.
 *
 * @param epsNodeId   the node that was updated
 * @param name        the node's name (for confirmation)
 * @param oldCode     the code before the update
 * @param newCode     the code after the update
 * @param rowsUpdated rows the UPDATE touched (1 on success)
 */
public record EpsCodeCorrectionResponse(
        UUID epsNodeId,
        String name,
        String oldCode,
        String newCode,
        int rowsUpdated
) {
}
