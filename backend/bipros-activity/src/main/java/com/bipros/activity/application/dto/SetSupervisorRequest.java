package com.bipros.activity.application.dto;

import java.util.UUID;

// Phase 4.5: sets Activity.supervisor_user_id. supervisorUserId=null clears the supervisor.
// supervisorName is a display snapshot from the frontend picker; ignored on persist (no
// snapshot column on Activity — display is resolved from public.users).
public record SetSupervisorRequest(
    UUID supervisorUserId,
    String supervisorName
) {}
