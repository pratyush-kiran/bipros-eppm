package com.bipros.common.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Creates and reads in-app notifications. Reusable cross-cutting service (à la AuditService). */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

  private final UserNotificationRepository repository;

  /** Persist a notification for {@code recipientUserId}. Null recipient is a no-op (returns null). */
  public UUID create(UUID recipientUserId, String type, String title, String body,
                     String linkUrl, UUID projectId, UUID relatedEntityId) {
    if (recipientUserId == null) return null;
    UserNotification n = UserNotification.builder()
        .recipientUserId(recipientUserId).type(type).title(title).body(body)
        .linkUrl(linkUrl).projectId(projectId).relatedEntityId(relatedEntityId).build();
    return repository.save(n).getId();
  }

  @Transactional(readOnly = true)
  public Page<UserNotification> list(UUID recipientUserId, boolean unreadOnly, Pageable pageable) {
    return unreadOnly
        ? repository.findByRecipientUserIdAndReadAtIsNullOrderByCreatedAtDesc(recipientUserId, pageable)
        : repository.findByRecipientUserIdOrderByCreatedAtDesc(recipientUserId, pageable);
  }

  @Transactional(readOnly = true)
  public long unreadCount(UUID recipientUserId) {
    return repository.countByRecipientUserIdAndReadAtIsNull(recipientUserId);
  }

  /** Mark one notification read — only if it belongs to {@code recipientUserId} and is currently unread. */
  @Transactional
  public boolean markRead(UUID recipientUserId, UUID notificationId) {
    return repository.findById(notificationId)
        .filter(n -> recipientUserId != null && recipientUserId.equals(n.getRecipientUserId()))
        .filter(n -> n.getReadAt() == null)
        .map(n -> { n.setReadAt(Instant.now()); repository.save(n); return true; })
        .orElse(false);
  }

  @Transactional
  public int markAllRead(UUID recipientUserId) {
    return repository.markAllReadFor(recipientUserId, Instant.now());
  }

  /** Dedup helper: has a notification of {@code type} for this {@code relatedEntityId}+recipient been created at/after {@code since}? */
  @Transactional(readOnly = true)
  public boolean existsSince(UUID relatedEntityId, String type, UUID recipientUserId, Instant since) {
    if (since == null) return false; // null submittedAt → treat as "no prior", always notify
    return repository.existsByRelatedEntityIdAndTypeAndRecipientUserIdAndCreatedAtGreaterThanEqual(
        relatedEntityId, type, recipientUserId, since);
  }
}
