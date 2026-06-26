package com.bipros.api.controller;

import com.bipros.api.dto.NotificationResponse;
import com.bipros.common.dto.ApiResponse;
import com.bipros.common.dto.PagedResponse;
import com.bipros.common.notification.NotificationService;
import com.bipros.common.notification.UserNotification;
import com.bipros.common.security.ProjectAccessGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

  private final NotificationService notificationService;
  private final ProjectAccessGuard projectAccessGuard;

  @GetMapping
  public ResponseEntity<ApiResponse<PagedResponse<NotificationResponse>>> list(
      @RequestParam(defaultValue = "false") boolean unreadOnly,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    UUID me = projectAccessGuard.currentUserId();
    Page<UserNotification> p = notificationService.list(me, unreadOnly, PageRequest.of(page, size));
    List<NotificationResponse> content = p.getContent().stream().map(NotificationResponse::from).toList();
    PagedResponse<NotificationResponse> paged = PagedResponse.of(
        content, p.getTotalElements(), p.getTotalPages(), p.getNumber(), p.getSize());
    return ResponseEntity.ok(ApiResponse.ok(paged));
  }

  @GetMapping("/unread-count")
  public ResponseEntity<ApiResponse<UnreadCountResponse>> unreadCount() {
    UUID me = projectAccessGuard.currentUserId();
    return ResponseEntity.ok(ApiResponse.ok(new UnreadCountResponse(notificationService.unreadCount(me))));
  }

  @PatchMapping("/{id}/read")
  public ResponseEntity<ApiResponse<Boolean>> markRead(@PathVariable UUID id) {
    UUID me = projectAccessGuard.currentUserId();
    return ResponseEntity.ok(ApiResponse.ok(notificationService.markRead(me, id)));
  }

  @PostMapping("/read-all")
  public ResponseEntity<ApiResponse<Integer>> markAllRead() {
    UUID me = projectAccessGuard.currentUserId();
    return ResponseEntity.ok(ApiResponse.ok(notificationService.markAllRead(me)));
  }

  public record UnreadCountResponse(long count) {}
}
