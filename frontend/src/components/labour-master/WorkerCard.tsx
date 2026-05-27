"use client";

import type { LabourDesignationResponse } from "@/lib/api/labourMasterApi";
import { CATEGORY_ACCENT, GRADE_BADGE, formatOMR } from "./labourMasterTokens";

type Props = {
  designation: LabourDesignationResponse;
  onClick?: (d: LabourDesignationResponse) => void;
};

export function WorkerCard({ designation, onClick }: Props) {
  const accent = CATEGORY_ACCENT[designation.category];
  const grade = GRADE_BADGE[designation.grade];
  const workerCount = designation.deployment?.workerCount ?? 0;
  const dailyRate = designation.deployment?.effectiveRate ?? designation.defaultDailyRate;

  return (
    <button
      type="button"
      onClick={onClick ? () => onClick(designation) : undefined}
      className={`group relative w-full overflow-hidden rounded-xl border bg-paper p-4 text-left transition-all duration-200 hover:-translate-y-0.5 hover:shadow-[0_8px_22px_-12px_rgba(0,88,202,0.20)] ${accent.cardBorder}`}
    >
      <div className={`pointer-events-none absolute inset-x-0 top-0 h-[3px] ${accent.stripe} opacity-70`} />

      <div className="flex items-start justify-between gap-2">
        <span className="font-mono text-[11px] font-semibold tracking-[0.04em] text-gold-ink">
          {designation.code}
        </span>
        <span className={`inline-flex items-center rounded border px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wider ${grade}`}>
          Grade {designation.grade}
        </span>
      </div>

      <h3 className="mt-2 font-display text-[17px] font-semibold leading-tight text-charcoal">
        {designation.designation}
      </h3>
      <p className="mt-0.5 text-[12px] text-slate">{designation.trade}</p>

      <div className="mt-3 flex flex-wrap gap-1.5">
        {designation.skills.slice(0, 4).map((s) => (
          <span
            key={s}
            className={`inline-flex max-w-[18ch] truncate rounded-md border px-1.5 py-0.5 text-[10px] ${accent.chip}`}
            title={s}
          >
            {s}
          </span>
        ))}
        {designation.skills.length > 4 && (
          <span className="inline-flex rounded-md border border-hairline bg-ivory px-1.5 py-0.5 text-[10px] text-slate">
            +{designation.skills.length - 4}
          </span>
        )}
      </div>

      <div className="mt-4 flex items-end justify-between border-t border-hairline pt-3">
        <div>
          <div className="text-[10px] uppercase tracking-[0.12em] text-slate">Workers</div>
          <div className="font-display text-[20px] font-semibold leading-none text-charcoal">
            {workerCount}
          </div>
        </div>
        <div className="text-right">
          <div className="text-[10px] uppercase tracking-[0.12em] text-slate">Daily rate</div>
          <div className="font-display text-[20px] font-semibold leading-none text-gold-deep">
            {formatOMR(dailyRate)}
          </div>
        </div>
      </div>

      <div className="mt-2 text-[11px] text-ash">
        {designation.nationality.replace(/_/g, " / ")} · {designation.experienceYearsMin}+ yrs
      </div>
    </button>
  );
}
