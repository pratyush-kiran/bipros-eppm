package com.bipros.common.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private UserNotificationRepository repository;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(repository);
    }

    // -----------------------------------------------------------------------
    // 1. create — happy path
    // -----------------------------------------------------------------------
    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("saves entity with all provided fields and returns the saved id")
        void savesEntityWithCorrectFieldsAndReturnsId() {
            UUID recipientId    = UUID.randomUUID();
            UUID projectId      = UUID.randomUUID();
            UUID relatedId      = UUID.randomUUID();
            UUID expectedId     = UUID.randomUUID();
            String type         = "DPR_SUBMITTED";
            String title        = "DPR submitted for review";
            String body         = "DPR for 2026-06-25 is awaiting approval.";
            String linkUrl      = "/projects/123/dpr";

            UserNotification saved = new UserNotification();
            saved.setId(expectedId);
            when(repository.save(any(UserNotification.class))).thenReturn(saved);

            UUID returnedId = service.create(recipientId, type, title, body, linkUrl, projectId, relatedId);

            assertThat(returnedId).isEqualTo(expectedId);

            ArgumentCaptor<UserNotification> captor = ArgumentCaptor.forClass(UserNotification.class);
            verify(repository).save(captor.capture());
            UserNotification captured = captor.getValue();
            assertThat(captured.getRecipientUserId()).isEqualTo(recipientId);
            assertThat(captured.getType()).isEqualTo(type);
            assertThat(captured.getTitle()).isEqualTo(title);
            assertThat(captured.getBody()).isEqualTo(body);
            assertThat(captured.getLinkUrl()).isEqualTo(linkUrl);
            assertThat(captured.getProjectId()).isEqualTo(projectId);
            assertThat(captured.getRelatedEntityId()).isEqualTo(relatedId);
        }

        // -------------------------------------------------------------------
        // 2. create — null recipient is a no-op
        // -------------------------------------------------------------------
        @Test
        @DisplayName("returns null and never calls repository when recipientUserId is null")
        void nullRecipientReturnsNullWithNoRepoInteraction() {
            UUID result = service.create(null, "T", "Title", "Body", null, null, null);

            assertThat(result).isNull();
            verifyNoInteractions(repository);
        }
    }

    // -----------------------------------------------------------------------
    // 3. list — unreadOnly flag routes to correct finder
    // -----------------------------------------------------------------------
    @Nested
    @DisplayName("list")
    class ListTests {

        private final UUID uid      = UUID.randomUUID();
        private final Pageable page = PageRequest.of(0, 10);

        @Test
        @DisplayName("unreadOnly=true calls findByRecipientUserIdAndReadAtIsNullOrderByCreatedAtDesc")
        void unreadOnlyCallsUnreadFinder() {
            Page<UserNotification> expected = new PageImpl<>(List.of());
            when(repository.findByRecipientUserIdAndReadAtIsNullOrderByCreatedAtDesc(uid, page))
                    .thenReturn(expected);

            Page<UserNotification> result = service.list(uid, true, page);

            assertThat(result).isSameAs(expected);
            verify(repository).findByRecipientUserIdAndReadAtIsNullOrderByCreatedAtDesc(uid, page);
            verify(repository, never()).findByRecipientUserIdOrderByCreatedAtDesc(any(), any());
        }

        @Test
        @DisplayName("unreadOnly=false calls findByRecipientUserIdOrderByCreatedAtDesc")
        void allCallsAllFinder() {
            Page<UserNotification> expected = new PageImpl<>(List.of());
            when(repository.findByRecipientUserIdOrderByCreatedAtDesc(uid, page))
                    .thenReturn(expected);

            Page<UserNotification> result = service.list(uid, false, page);

            assertThat(result).isSameAs(expected);
            verify(repository).findByRecipientUserIdOrderByCreatedAtDesc(uid, page);
            verify(repository, never()).findByRecipientUserIdAndReadAtIsNullOrderByCreatedAtDesc(any(), any());
        }
    }

    // -----------------------------------------------------------------------
    // 4. unreadCount — delegates to countByRecipientUserIdAndReadAtIsNull
    // -----------------------------------------------------------------------
    @Nested
    @DisplayName("unreadCount")
    class UnreadCount {

        @Test
        @DisplayName("delegates to countByRecipientUserIdAndReadAtIsNull and returns its value")
        void delegatesAndReturnsCount() {
            UUID uid = UUID.randomUUID();
            when(repository.countByRecipientUserIdAndReadAtIsNull(uid)).thenReturn(7L);

            long count = service.unreadCount(uid);

            assertThat(count).isEqualTo(7L);
            verify(repository).countByRecipientUserIdAndReadAtIsNull(uid);
        }
    }

    // -----------------------------------------------------------------------
    // 5. markRead — four sub-cases
    // -----------------------------------------------------------------------
    @Nested
    @DisplayName("markRead")
    class MarkRead {

        private final UUID uid            = UUID.randomUUID();
        private final UUID notificationId = UUID.randomUUID();

        @Test
        @DisplayName("owned + unread: sets readAt, saves, returns true")
        void ownedAndUnreadSetsReadAtAndReturnsTrue() {
            UserNotification n = new UserNotification();
            n.setRecipientUserId(uid);
            n.setReadAt(null);
            when(repository.findById(notificationId)).thenReturn(Optional.of(n));
            when(repository.save(n)).thenReturn(n);

            boolean result = service.markRead(uid, notificationId);

            assertThat(result).isTrue();
            assertThat(n.getReadAt()).isNotNull();
            verify(repository).save(n);
        }

        @Test
        @DisplayName("recipient mismatch: returns false, save NOT called")
        void recipientMismatchReturnsFalse() {
            UUID otherId = UUID.randomUUID();
            UserNotification n = new UserNotification();
            n.setRecipientUserId(otherId); // belongs to someone else
            n.setReadAt(null);
            when(repository.findById(notificationId)).thenReturn(Optional.of(n));

            boolean result = service.markRead(uid, notificationId);

            assertThat(result).isFalse();
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("already read: returns false, save NOT called")
        void alreadyReadReturnsFalse() {
            UserNotification n = new UserNotification();
            n.setRecipientUserId(uid);
            n.setReadAt(Instant.now().minusSeconds(60)); // already read
            when(repository.findById(notificationId)).thenReturn(Optional.of(n));

            boolean result = service.markRead(uid, notificationId);

            assertThat(result).isFalse();
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("unknown id (findById empty): returns false")
        void unknownIdReturnsFalse() {
            when(repository.findById(notificationId)).thenReturn(Optional.empty());

            boolean result = service.markRead(uid, notificationId);

            assertThat(result).isFalse();
            verify(repository, never()).save(any());
        }
    }

    // -----------------------------------------------------------------------
    // 6. markAllRead — delegates to markAllReadFor
    // -----------------------------------------------------------------------
    @Nested
    @DisplayName("markAllRead")
    class MarkAllRead {

        @Test
        @DisplayName("delegates to markAllReadFor(uid, <some Instant>) and returns its int")
        void delegatesAndReturnsCount() {
            UUID uid = UUID.randomUUID();
            when(repository.markAllReadFor(eq(uid), any(Instant.class))).thenReturn(5);

            int result = service.markAllRead(uid);

            assertThat(result).isEqualTo(5);
            verify(repository).markAllReadFor(eq(uid), any(Instant.class));
        }
    }

    // -----------------------------------------------------------------------
    // 7. existsSince — delegates to dedup finder; null since → false, no repo hit
    // -----------------------------------------------------------------------
    @Nested
    @DisplayName("existsSince")
    class ExistsSince {

        @Test
        @DisplayName("delegates to dedup finder and returns its value when since is non-null")
        void delegatesToDedupFinder() {
            UUID relatedId = UUID.randomUUID();
            UUID uid       = UUID.randomUUID();
            Instant since  = Instant.now().minusSeconds(300);
            when(repository.existsByRelatedEntityIdAndTypeAndRecipientUserIdAndCreatedAtGreaterThanEqual(
                    relatedId, "DPR_SUBMITTED", uid, since)).thenReturn(true);

            boolean result = service.existsSince(relatedId, "DPR_SUBMITTED", uid, since);

            assertThat(result).isTrue();
            verify(repository).existsByRelatedEntityIdAndTypeAndRecipientUserIdAndCreatedAtGreaterThanEqual(
                    relatedId, "DPR_SUBMITTED", uid, since);
        }

        @Test
        @DisplayName("null since returns false without hitting the repository")
        void nullSinceReturnsFalseWithNoRepoInteraction() {
            UUID relatedId = UUID.randomUUID();
            UUID uid       = UUID.randomUUID();

            boolean result = service.existsSince(relatedId, "DPR_SUBMITTED", uid, null);

            assertThat(result).isFalse();
            verifyNoInteractions(repository);
        }
    }
}
