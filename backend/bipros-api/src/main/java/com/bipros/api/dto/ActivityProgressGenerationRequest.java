package com.bipros.api.dto;

import lombok.Data;
import java.util.List;
import java.util.UUID;

/** Request for the admin auto-DPR-progress generator. All fields optional; defaults applied in the service. */
@Data
public class ActivityProgressGenerationRequest {
  private boolean dryRun = true;                 // preview only by default
  private Integer targetPercentMin = 40;
  private Integer targetPercentMax = 60;
  private UUID wbsNodeId;                         // optional subtree filter
  private List<UUID> activityIds;                 // optional explicit list
  private Integer datesPerActivity = 3;
  private boolean renameDuplicates = true;
  private boolean autoLockDraft = true;
  private boolean includeResources = true;
  private boolean includeSubContractors = true;
  private Integer workingHoursPerDay = 8;
}
