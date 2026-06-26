package com.bipros.api.listener;

import com.bipros.api.notification.DprNotificationType;
import com.bipros.api.service.DprNotificationRecipientResolver;
import com.bipros.common.event.DprMutationType;
import com.bipros.common.event.DprSubmittedEvent;
import com.bipros.common.notification.NotificationService;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class DprApprovalNotificationListener {

  private final DailyProgressReportRepository dprRepository;
  private final NotificationService notificationService;
  private final DprNotificationRecipientResolver recipientResolver;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onDpr(DprSubmittedEvent e) {
    if (e.eventType() == DprMutationType.DELETED) return;
    DailyProgressReport dpr = dprRepository.findById(e.dprId()).orElse(null);
    if (dpr == null || dpr.getApprovalStatus() == null) return;
    String link = "/projects/" + dpr.getProjectId() + "/dpr?focus=" + dpr.getId();
    try {
      switch (dpr.getApprovalStatus()) {
        case SUBMITTED -> notifyArrived(dpr, link);
        case APPROVED -> notificationService.create(dpr.getSubmittedByUserId(),
            DprNotificationType.DPR_APPROVED, "DPR approved",
            "Your DPR for %s on %s was approved.".formatted(dpr.getActivityName(), dpr.getReportDate()),
            link, dpr.getProjectId(), dpr.getId());
        case REJECTED -> notificationService.create(dpr.getSubmittedByUserId(),
            DprNotificationType.DPR_REJECTED, "DPR rejected",
            "Your DPR for %s on %s was rejected: %s. Edit and resubmit.".formatted(
                dpr.getActivityName(), dpr.getReportDate(),
                dpr.getRejectionReason() != null ? dpr.getRejectionReason() : "see remarks"),
            link, dpr.getProjectId(), dpr.getId());
        default -> { /* DRAFT: no-op */ }
      }
    } catch (Exception ex) {
      log.warn("[DprApprovalNotificationListener] failed for dpr={}: {}", dpr.getId(), ex.getMessage(), ex);
    }
  }

  private void notifyArrived(DailyProgressReport dpr, String link) {
    String title = "DPR awaiting approval";
    String body = "DPR for %s by %s on %s is awaiting your approval.".formatted(
        dpr.getActivityName(), dpr.getSupervisorName(), dpr.getReportDate());
    for (UUID recipient : recipientResolver.arrivalRecipients(dpr)) {
      if (notificationService.existsSince(dpr.getId(),
          DprNotificationType.DPR_SUBMITTED_FOR_APPROVAL, recipient, dpr.getSubmittedAt())) {
        continue; // dedup: already notified this submission cycle
      }
      notificationService.create(recipient, DprNotificationType.DPR_SUBMITTED_FOR_APPROVAL,
          title, body, link, dpr.getProjectId(), dpr.getId());
    }
  }
}
