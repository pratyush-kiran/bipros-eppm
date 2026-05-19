"use client";

export type Cadence = "D" | "W" | "M";

const OPTIONS: { value: Cadence; label: string }[] = [
  { value: "D", label: "Daily" },
  { value: "W", label: "Weekly" },
  { value: "M", label: "Monthly" },
];

export function CadenceToggle({
  value,
  onChange,
}: {
  value: Cadence;
  onChange: (next: Cadence) => void;
}) {
  return (
    <div className="inline-flex rounded-lg border border-hairline bg-paper p-1 shadow-sm">
      {OPTIONS.map((opt) => {
        const active = opt.value === value;
        return (
          <button
            key={opt.value}
            type="button"
            onClick={() => onChange(opt.value)}
            className={
              "rounded-md px-3 py-1.5 text-sm font-medium transition-colors " +
              (active
                ? "bg-gold-tint/70 text-charcoal shadow-inner"
                : "text-slate hover:text-charcoal")
            }
            aria-pressed={active}
          >
            {opt.label}
          </button>
        );
      })}
    </div>
  );
}
