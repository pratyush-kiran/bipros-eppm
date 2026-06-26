package com.bipros.api.scheduling;

import com.bipros.api.notification.DprNotificationType;
import com.bipros.api.service.DprNotificationRecipientResolver;
import com.bipros.api.service.DprSlaConfig;
import com.bipros.common.notification.NotificationService;
import com.bipros.common.scheduling.ScheduledJobLeaseRepository;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprApprovalStatus;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Escalates DPRs pending approval past the SLA window (one-shot, lease-guarded). Mirrors PermitEscalationJob. */
@Component
@RequiredArgsConstructor
@Slf4j
public class DprApprovalSlaEscalationJob {

  private static final String JOB_NAME = "dpr_approval_sla_escalation";

  private final DailyProgressReportRepository dprRepository;
  private final ScheduledJobLeaseRepository leaseRepository;
  private final DprSlaConfig slaConfig;
  private final NotificationService notificationService;
  private final DprNotificationRecipientResolver recipientResolver;

  @Scheduled(fixedDelayString = "${bipros.dpr.sla-escalation-fixed-delay-ms:1800000}")
  @Transactional
  public void run() {
    Instant now = Instant.now();
    Instant until = now.plus(Duration.ofMinutes(10));
    String owner = "node-" + UUID.randomUUID();
    if (leaseRepository.tryAcquire(JOB_NAME, until, now, owner) == 0) {
      return;   // another node holds the lease
    }

    int slaHours = slaConfig.slaHours();
    Instant cutoff = now.minus(Duration.ofHours(slaHours));
    var overdue = dprRepository.findByApprovalStatusAndSubmittedAtBeforeAndEscalatedAtIsNull(
        DprApprovalStatus.SUBMITTED, cutoff);

    int escalated = 0;
    for (DailyProgressReport dpr : overdue) {
      try {
        long n = dpr.getSubmittedAt() != null ? Duration.between(dpr.getSubmittedAt(), now).toHours() : slaHours;
        String link = "/projects/" + dpr.getProjectId() + "/dpr?focus=" + dpr.getId();
        if (dpr.getAssignedApproverUserId() != null) {
          notificationService.create(dpr.getAssignedApproverUserId(),
              DprNotificationType.DPR_APPROVAL_OVERDUE_APPROVER, "DPR approval overdue",
              "You haven't actioned %s's DPR for %s (%dh overdue).".formatted(
                  dpr.getSupervisorName(), dpr.getActivityName(), n),
              link, dpr.getProjectId(), dpr.getId());
        }
        for (UUID mgr : recipientResolver.escalationManagers(dpr)) {
          notificationService.create(mgr,
              DprNotificationType.DPR_APPROVAL_OVERDUE_ESCALATION, "DPR approval overdue (escalation)",
              "A DPR by %s for %s has been awaiting approval %dh.".formatted(
                  dpr.getSupervisorName(), dpr.getActivityName(), n),
              link, dpr.getProjectId(), dpr.getId());
        }
        dpr.setEscalatedAt(now);   // one-shot; managed entity → flushed at tx commit
        escalated++;
      } catch (Exception ex) {
        log.warn("[DprApprovalSlaEscalationJob] failed for dpr={}: {}", dpr.getId(), ex.getMessage(), ex);
      }
    }
    if (escalated > 0) log.info("DprApprovalSlaEscalationJob escalated {} DPRs", escalated);
  }
}
