package com.bipros.common.notification;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A persisted in-app notification for one recipient. Reusable cross-cutting infra (à la AuditLog). */
@Entity
@Table(name = "user_notifications", schema = "public",
    indexes = {
        @Index(name = "idx_user_notif_recipient_read", columnList = "recipient_user_id, read_at"),
        @Index(name = "idx_user_notif_related", columnList = "related_entity_id")
    })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserNotification extends BaseEntity {

  @Column(name = "recipient_user_id", nullable = false) private UUID recipientUserId;
  @Column(name = "type", nullable = false, length = 60)  private String type;
  @Column(name = "title", nullable = false, length = 200) private String title;
  @Column(name = "body", nullable = false, length = 1000) private String body;
  @Column(name = "link_url", length = 500)               private String linkUrl;
  @Column(name = "project_id")                           private UUID projectId;
  @Column(name = "related_entity_id")                    private UUID relatedEntityId;
  @Column(name = "read_at")                              private Instant readAt;
}
