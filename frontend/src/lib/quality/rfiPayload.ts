import type { RfiRegister, UpdateRfiRequest } from "@/lib/api/documentApi";

export interface RfiEditForm {
  subject: string;
  description?: string;
  priority: string;
  status: string;
  assignedTo: string;
  dueDate: string;
  response?: string;
  closedDate?: string | null;
}

/** Carries the backend-required immutable fields (rfiNumber/raisedBy/raisedDate)
 *  from the loaded RFI so an update PUT passes @NotBlank/@NotNull validation. */
export function buildRfiUpdatePayload(rfi: RfiRegister, form: RfiEditForm): UpdateRfiRequest {
  return {
    rfiNumber: rfi.rfiNumber,
    raisedBy: rfi.raisedBy,
    raisedDate: rfi.raisedDate,
    subject: form.subject,
    description: form.description,
    priority: form.priority,
    status: form.status,
    assignedTo: form.assignedTo,
    dueDate: form.dueDate,
    response: form.response,
    closedDate: form.closedDate ?? null,
  };
}
