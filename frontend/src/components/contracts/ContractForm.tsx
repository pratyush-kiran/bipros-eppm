"use client";

import type {
  BillingCycle,
  ContractResponse,
  ContractType,
  CreateContractRequest,
} from "@/lib/types";
import {
  BILLING_CYCLE_OPTIONS,
  CONTRACT_TYPE_OPTIONS,
} from "@/lib/contracts/contractTypeOptions";
import { SecretField } from "@/components/auth/SecretField";

const FINANCE_ROLES = ["ROLE_FINANCE", "ROLE_ADMIN"] as const;

const inputClass =
  "w-full px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary placeholder-text-muted focus:border-accent focus:ring-2 focus:ring-accent focus:ring-opacity-50";
const labelClass = "block text-sm font-medium text-text-secondary mb-1";
const sectionHeaderClass =
  "text-xs uppercase tracking-wide text-text-muted font-semibold mt-4 mb-2";

interface ContractFormProps {
  projectId: string;
  initialValue?: Partial<ContractResponse>;
  isPending: boolean;
  submitLabel: string;
  onSubmit: (data: CreateContractRequest) => void;
  onCancel: () => void;
  /** Default ISO 4217 currency code for new contracts. Falls back to "INR". */
  defaultCurrency?: string;
}

/** Form-data → typed CreateContractRequest. Empty strings become undefined. */
function readForm(form: HTMLFormElement, projectId: string): CreateContractRequest {
  const fd = new FormData(form);
  const str = (k: string): string | undefined => {
    const v = fd.get(k);
    if (typeof v !== "string") return undefined;
    const t = v.trim();
    return t === "" ? undefined : t;
  };
  const num = (k: string): number | undefined => {
    const v = str(k);
    if (v === undefined) return undefined;
    const n = parseFloat(v);
    return Number.isFinite(n) ? n : undefined;
  };
  const intNum = (k: string): number | undefined => {
    const v = str(k);
    if (v === undefined) return undefined;
    const n = parseInt(v, 10);
    return Number.isFinite(n) ? n : undefined;
  };

  return {
    projectId,
    contractNumber: str("contractNumber") ?? "",
    contractorName: str("contractorName") ?? "",
    contractType: (str("contractType") ?? "EPC") as ContractType,
    contractValue: num("contractValue") ?? 0,
    revisedValue: num("revisedValue"),
    loaNumber: str("loaNumber"),
    contractorCode: str("contractorCode"),
    loaDate: str("loaDate") ?? "",
    startDate: str("startDate") ?? "",
    completionDate: str("completionDate") ?? "",
    revisedCompletionDate: str("revisedCompletionDate"),
    dlpMonths: intNum("dlpMonths"),
    ldRate: num("ldRate") ?? 0,
    description: str("description"),
    currency: str("currency") ?? "INR",
    ntpDate: str("ntpDate"),
    mobilisationAdvancePct: num("mobilisationAdvancePct"),
    retentionPct: num("retentionPct"),
    performanceBgPct: num("performanceBgPct"),
    paymentTermsDays: intNum("paymentTermsDays"),
    billingCycle: (str("billingCycle") as BillingCycle | undefined) ?? undefined,
  };
}

export function ContractForm({
  projectId,
  initialValue,
  isPending,
  submitLabel,
  onSubmit,
  onCancel,
  defaultCurrency = "INR",
}: ContractFormProps) {
  const handleSubmit = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    onSubmit(readForm(e.currentTarget, projectId));
  };

  const v = initialValue ?? {};

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div className={sectionHeaderClass}>Identification</div>
      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className={labelClass}>Contract Number *</label>
          <input
            type="text"
            name="contractNumber"
            required
            defaultValue={v.contractNumber ?? ""}
            placeholder="CON-2024-001"
            className={inputClass}
          />
        </div>
        <div>
          <label className={labelClass}>LOA Number</label>
          <input
            type="text"
            name="loaNumber"
            defaultValue={v.loaNumber ?? ""}
            placeholder="Optional"
            className={inputClass}
          />
        </div>
      </div>

      <div className={sectionHeaderClass}>Parties</div>
      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className={labelClass}>Contractor Name *</label>
          <input
            type="text"
            name="contractorName"
            required
            defaultValue={v.contractorName ?? ""}
            placeholder="Company Name"
            className={inputClass}
          />
        </div>
        <div>
          <label className={labelClass}>Contractor Code</label>
          <input
            type="text"
            name="contractorCode"
            defaultValue={v.contractorCode ?? ""}
            placeholder="Optional"
            className={inputClass}
          />
        </div>
      </div>

      <div className={sectionHeaderClass}>Type</div>
      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className={labelClass}>Contract Type *</label>
          <select
            name="contractType"
            required
            defaultValue={v.contractType ?? ""}
            className={inputClass}
          >
            <option value="">Select Type</option>
            {CONTRACT_TYPE_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label className={labelClass}>Currency</label>
          <input
            type="text"
            name="currency"
            maxLength={3}
            defaultValue={v.currency ?? defaultCurrency}
            placeholder={defaultCurrency}
            className={inputClass}
          />
        </div>
      </div>

      {/* Money fields are FINANCE/ADMIN-only. Backend strips contractValue/revisedValue from
          the response payload via @JsonView for non-finance callers; SecretField keeps the UI
          tidy by replacing the inputs with a non-editable hint when those fields aren't
          available. */}
      <SecretField
        visibleTo={FINANCE_ROLES}
        masked={
          <>
            <div className={sectionHeaderClass}>Value</div>
            <div className="rounded-lg border border-border bg-surface-hover px-3 py-2 text-sm text-text-muted">
              Contract value is restricted to Finance roles.
            </div>
          </>
        }
      >
        <div className={sectionHeaderClass}>Value</div>
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className={labelClass}>Contract Value *</label>
            <input
              type="number"
              name="contractValue"
              required
              step="0.01"
              defaultValue={v.contractValue ?? ""}
              placeholder="0.00"
              className={inputClass}
            />
          </div>
          <div>
            <label className={labelClass}>Revised Value</label>
            <input
              type="number"
              name="revisedValue"
              step="0.01"
              defaultValue={v.revisedValue ?? ""}
              placeholder="Auto-updates as VOs are approved"
              className={inputClass}
            />
          </div>
        </div>
      </SecretField>

      <div className={sectionHeaderClass}>Dates</div>
      <div className="grid grid-cols-3 gap-4">
        <div>
          <label className={labelClass}>LOA Date *</label>
          <input
            type="date"
            name="loaDate"
            required
            defaultValue={v.loaDate ?? ""}
            className={inputClass}
          />
        </div>
        <div>
          <label className={labelClass}>NTP Date</label>
          <input
            type="date"
            name="ntpDate"
            defaultValue={v.ntpDate ?? ""}
            className={inputClass}
          />
        </div>
        <div>
          <label className={labelClass}>Start Date *</label>
          <input
            type="date"
            name="startDate"
            required
            defaultValue={v.startDate ?? ""}
            className={inputClass}
          />
        </div>
        <div>
          <label className={labelClass}>Completion Date *</label>
          <input
            type="date"
            name="completionDate"
            required
            defaultValue={v.completionDate ?? ""}
            className={inputClass}
          />
        </div>
        <div>
          <label className={labelClass}>Revised Completion Date</label>
          <input
            type="date"
            name="revisedCompletionDate"
            defaultValue={v.revisedCompletionDate ?? ""}
            className={inputClass}
          />
        </div>
      </div>

      <div className={sectionHeaderClass}>Commercial terms</div>
      <div className="grid grid-cols-3 gap-4">
        <div>
          <label className={labelClass}>DLP Months</label>
          <input
            type="number"
            name="dlpMonths"
            step="1"
            defaultValue={v.dlpMonths ?? 12}
            placeholder="12"
            className={inputClass}
          />
        </div>
        <div>
          <label className={labelClass}>LD Rate (%) *</label>
          <input
            type="number"
            name="ldRate"
            required
            step="0.01"
            defaultValue={v.ldRate ?? ""}
            placeholder="0.00"
            className={inputClass}
          />
        </div>
        <div>
          <label className={labelClass}>Mobilisation Advance (%)</label>
          <input
            type="number"
            name="mobilisationAdvancePct"
            step="0.01"
            defaultValue={v.mobilisationAdvancePct ?? ""}
            placeholder="e.g. 10"
            className={inputClass}
          />
        </div>
        <div>
          <label className={labelClass}>Retention (%)</label>
          <input
            type="number"
            name="retentionPct"
            step="0.01"
            defaultValue={v.retentionPct ?? ""}
            placeholder="e.g. 5"
            className={inputClass}
          />
        </div>
        <div>
          <label className={labelClass}>Performance BG (%)</label>
          <input
            type="number"
            name="performanceBgPct"
            step="0.01"
            defaultValue={v.performanceBgPct ?? ""}
            placeholder="e.g. 10"
            className={inputClass}
          />
        </div>
        <div>
          <label className={labelClass}>Payment Terms (days)</label>
          <input
            type="number"
            name="paymentTermsDays"
            step="1"
            defaultValue={v.paymentTermsDays ?? ""}
            placeholder="e.g. 45"
            className={inputClass}
          />
        </div>
        <div>
          <label className={labelClass}>Billing Cycle</label>
          <select
            name="billingCycle"
            defaultValue={v.billingCycle ?? ""}
            className={inputClass}
          >
            <option value="">Not specified</option>
            {BILLING_CYCLE_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
          </select>
        </div>
      </div>

      <div className={sectionHeaderClass}>Description</div>
      <div>
        <label className={labelClass}>Scope / Description</label>
        <textarea
          name="description"
          rows={3}
          defaultValue={v.description ?? ""}
          placeholder="Brief scope of work covered by this contract."
          className={inputClass}
        />
      </div>

      <div className="flex gap-2 pt-2">
        <button
          type="submit"
          disabled={isPending}
          className="px-4 py-2 bg-green-600 text-text-primary rounded-lg hover:bg-green-600 disabled:bg-border transition-colors"
        >
          {isPending ? "Saving…" : submitLabel}
        </button>
        <button
          type="button"
          onClick={onCancel}
          className="px-4 py-2 bg-surface-active/50 text-text-secondary rounded-lg hover:bg-border transition-colors"
        >
          Cancel
        </button>
      </div>
    </form>
  );
}
