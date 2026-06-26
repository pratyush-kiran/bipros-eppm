package com.bipros.api.controller;

import com.bipros.api.dto.NotificationResponse;
import com.bipros.common.dto.ApiResponse;
import com.bipros.common.dto.PagedResponse;
import com.bipros.common.notification.NotificationService;
import com.bipros.common.notification.UserNotification;
import com.bipros.common.security.ProjectAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

  @Mock
  NotificationService notificationService;

  @Mock
  ProjectAccessGuard projectAccessGuard;

  NotificationController controller;

  private static final UUID ME = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    controller = new NotificationController(notificationService, projectAccessGuard);
    when(projectAccessGuard.currentUserId()).thenReturn(ME);
  }

  private static UserNotification aNotification() {
    UserNotification n = UserNotification.builder()
        .recipientUserId(ME)
        .type("DPR_SUBMITTED")
        .title("DPR submitted")
        .body("A DPR was submitted for review.")
        .linkUrl("/projects/abc/dpr")
        .projectId(UUID.randomUUID())
        .relatedEntityId(UUID.randomUUID())
        .build();
    // Manually set BaseEntity fields that are normally populated by JPA auditing
    n.setId(UUID.randomUUID());
    n.setCreatedAt(Instant.now());
    return n;
  }

  @Test
  void list_delegatesToServiceAndWrapsPagedResponse() {
    UserNotification notif = aNotification();
    Page<UserNotification> page = new PageImpl<>(List.of(notif), PageRequest.of(0, 20), 1);
    when(notificationService.list(ME, false, PageRequest.of(0, 20))).thenReturn(page);

    ResponseEntity<ApiResponse<PagedResponse<NotificationResponse>>> response =
        controller.list(false, 0, 20);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    ApiResponse<PagedResponse<NotificationResponse>> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.error()).isNull();
    PagedResponse<NotificationResponse> paged = body.data();
    assertThat(paged).isNotNull();
    assertThat(paged.content()).hasSize(1);
    NotificationResponse nr = paged.content().get(0);
    assertThat(nr.id()).isEqualTo(notif.getId());
    assertThat(nr.type()).isEqualTo("DPR_SUBMITTED");
    assertThat(paged.totalElements()).isEqualTo(1);
    assertThat(paged.currentPage()).isEqualTo(0);
    assertThat(paged.pageSize()).isEqualTo(20);
    verify(notificationService).list(ME, false, PageRequest.of(0, 20));
  }

  @Test
  void unreadCount_returnsServiceCount() {
    when(notificationService.unreadCount(ME)).thenReturn(7L);

    ResponseEntity<ApiResponse<NotificationController.UnreadCountResponse>> response =
        controller.unreadCount();

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    ApiResponse<NotificationController.UnreadCountResponse> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.error()).isNull();
    assertThat(body.data().count()).isEqualTo(7L);
    verify(notificationService).unreadCount(ME);
  }

  @Test
  void markRead_delegatesToServiceAndReturnsBooleanResult() {
    UUID notifId = UUID.randomUUID();
    when(notificationService.markRead(ME, notifId)).thenReturn(true);

    ResponseEntity<ApiResponse<Boolean>> response = controller.markRead(notifId);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    ApiResponse<Boolean> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.error()).isNull();
    assertThat(body.data()).isTrue();
    verify(notificationService).markRead(ME, notifId);
  }

  @Test
  void markAllRead_delegatesToServiceAndReturnsCount() {
    when(notificationService.markAllRead(ME)).thenReturn(5);

    ResponseEntity<ApiResponse<Integer>> response = controller.markAllRead();

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    ApiResponse<Integer> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.error()).isNull();
    assertThat(body.data()).isEqualTo(5);
    verify(notificationService).markAllRead(ME);
  }
}
