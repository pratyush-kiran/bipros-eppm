package com.bipros.api.dto;

import com.bipros.common.notification.UserNotification;
import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
    UUID id, String type, String title, String body, String linkUrl,
    UUID projectId, UUID relatedEntityId, Instant createdAt, Instant readAt) {

  public static NotificationResponse from(UserNotification n) {
    return new NotificationResponse(n.getId(), n.getType(), n.getTitle(), n.getBody(),
        n.getLinkUrl(), n.getProjectId(), n.getRelatedEntityId(), n.getCreatedAt(), n.getReadAt());
  }
}
