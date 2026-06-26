package com.bipros.common.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface UserNotificationRepository extends JpaRepository<UserNotification, UUID> {

  Page<UserNotification> findByRecipientUserIdOrderByCreatedAtDesc(UUID recipientUserId, Pageable pageable);

  Page<UserNotification> findByRecipientUserIdAndReadAtIsNullOrderByCreatedAtDesc(UUID recipientUserId, Pageable pageable);

  long countByRecipientUserIdAndReadAtIsNull(UUID recipientUserId);

  boolean existsByRelatedEntityIdAndTypeAndRecipientUserIdAndCreatedAtGreaterThanEqual(
      UUID relatedEntityId, String type, UUID recipientUserId, Instant createdAt);

  @Modifying
  @Query("update UserNotification n set n.readAt = :now where n.recipientUserId = :uid and n.readAt is null")
  int markAllReadFor(@Param("uid") UUID recipientUserId, @Param("now") Instant now);
}
