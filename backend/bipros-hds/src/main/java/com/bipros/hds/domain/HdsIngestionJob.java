package com.bipros.hds.domain;

import com.bipros.common.model.BaseEntity;
import com.bipros.hds.domain.enums.HdsIngestionStage;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "hds_ingestion_job", schema = "hds")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Builder
public class HdsIngestionJob extends BaseEntity {

    @Column(name = "hds_version_id", nullable = false)
    private UUID hdsVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 16)
    private HdsIngestionStage stage;

    @Column(name = "progress_pct", nullable = false)
    @Builder.Default
    private Integer progressPct = 0;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private Integer attemptCount = 0;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "worker_id", length = 64)
    private String workerId;
}
