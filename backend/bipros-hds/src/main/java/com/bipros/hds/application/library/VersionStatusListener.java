package com.bipros.hds.application.library;

import com.bipros.hds.application.retrieval.QueryCache;
import com.bipros.hds.domain.HdsVersion;
import com.bipros.hds.domain.enums.HdsVersionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Invalidates cached query answers when a version transitions to INDEXED or FAILED.
 *
 * <p>Wired manually from the ingestion orchestrator after a terminal status transition,
 * so it doesn't need to be a JPA entity listener (which can't be Spring-managed cleanly).
 */
@Component
@RequiredArgsConstructor
public class VersionStatusListener {

    private final QueryCache cache;

    public void onIndexedOrFailed(HdsVersion v) {
        if (v.getStatus() == HdsVersionStatus.INDEXED || v.getStatus() == HdsVersionStatus.FAILED) {
            cache.invalidateForVersion(v.getId());
        }
    }
}
