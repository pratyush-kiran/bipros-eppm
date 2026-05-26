"use client";

interface RagDonutProps {
  green: number;
  amber: number;
  red: number;
  size?: number;
  thickness?: number;
  centerLabel?: string;
  centerSubLabel?: string;
  className?: string;
}

export function RagDonut({
  green,
  amber,
  red,
  size = 132,
  thickness = 12,
  centerLabel,
  centerSubLabel,
  className,
}: RagDonutProps) {
  const total = green + amber + red;
  const r = (size - thickness) / 2;
  const c = 2 * Math.PI * r;
  const cx = size / 2;

  const segments = total > 0
    ? [
        { color: "var(--emerald)", value: green },
        { color: "var(--bronze-warn)", value: amber },
        { color: "var(--burgundy)", value: red },
      ]
    : [{ color: "var(--hairline)", value: 1 }];

  let offset = 0;

  return (
    <div className={className} style={{ width: size, height: size, position: "relative" }}>
      <svg
        width={size}
        height={size}
        viewBox={`0 0 ${size} ${size}`}
        style={{ transform: "rotate(-90deg)" }}
        aria-hidden
      >
        <circle
          cx={cx}
          cy={cx}
          r={r}
          fill="none"
          stroke="var(--hairline)"
          strokeWidth={thickness}
          opacity={0.4}
        />
        {segments.map((seg, i) => {
          const fraction = total > 0 ? seg.value / total : 1;
          const dash = c * fraction;
          const gap = c - dash;
          const dashoffset = -offset;
          offset += dash;
          return (
            <circle
              key={i}
              cx={cx}
              cy={cx}
              r={r}
              fill="none"
              stroke={seg.color}
              strokeWidth={thickness}
              strokeDasharray={`${dash} ${gap}`}
              strokeDashoffset={dashoffset}
              strokeLinecap="butt"
              style={{
                transition: "stroke-dasharray 600ms ease-out",
              }}
            />
          );
        })}
      </svg>
      {(centerLabel || centerSubLabel) && (
        <div
          style={{
            position: "absolute",
            inset: 0,
            display: "flex",
            flexDirection: "column",
            alignItems: "center",
            justifyContent: "center",
            pointerEvents: "none",
          }}
        >
          {centerLabel && (
            <span
              className="font-display text-[28px] font-semibold leading-none tracking-tight text-charcoal tabular-nums"
              style={{ fontVariationSettings: "'opsz' 144" }}
            >
              {centerLabel}
            </span>
          )}
          {centerSubLabel && (
            <span className="mt-1 text-[10px] font-semibold uppercase tracking-[0.14em] text-ash">
              {centerSubLabel}
            </span>
          )}
        </div>
      )}
    </div>
  );
}
