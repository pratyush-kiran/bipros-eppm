package com.bipros.api.notification;

public final class DprNotificationType {
  private DprNotificationType() {}
  public static final String DPR_SUBMITTED_FOR_APPROVAL     = "DPR_SUBMITTED_FOR_APPROVAL";
  public static final String DPR_APPROVAL_OVERDUE_APPROVER  = "DPR_APPROVAL_OVERDUE_APPROVER";
  public static final String DPR_APPROVAL_OVERDUE_ESCALATION = "DPR_APPROVAL_OVERDUE_ESCALATION";
  public static final String DPR_APPROVED                   = "DPR_APPROVED";
  public static final String DPR_REJECTED                   = "DPR_REJECTED";
}
