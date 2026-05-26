"use client";

import { useEffect, useRef, useState } from "react";

interface MetricNumberProps {
  value: number;
  format?: (n: number) => string;
  durationMs?: number;
  className?: string;
  style?: React.CSSProperties;
}

const defaultFormat = (n: number) => {
  if (Number.isInteger(n)) return n.toLocaleString();
  return n.toLocaleString(undefined, { maximumFractionDigits: 2 });
};

export function MetricNumber({
  value,
  format = defaultFormat,
  durationMs = 600,
  className,
  style,
}: MetricNumberProps) {
  const [display, setDisplay] = useState(0);
  const rafRef = useRef<number | null>(null);
  const startedRef = useRef(false);

  useEffect(() => {
    if (startedRef.current) {
      setDisplay(value);
      return;
    }
    startedRef.current = true;
    const start = performance.now();
    const from = 0;
    const to = value;

    const tick = (now: number) => {
      const elapsed = now - start;
      const t = Math.min(elapsed / durationMs, 1);
      // ease-out-cubic
      const eased = 1 - Math.pow(1 - t, 3);
      setDisplay(from + (to - from) * eased);
      if (t < 1) {
        rafRef.current = requestAnimationFrame(tick);
      } else {
        setDisplay(to);
      }
    };

    rafRef.current = requestAnimationFrame(tick);
    return () => {
      if (rafRef.current !== null) cancelAnimationFrame(rafRef.current);
    };
  }, [value, durationMs]);

  return (
    <span className={className} style={style}>
      {format(display)}
    </span>
  );
}
