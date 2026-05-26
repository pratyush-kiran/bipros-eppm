"use client";

import { useEffect, useRef, useState } from "react";

interface SparklineProps {
  values: number[];
  width?: number;
  height?: number;
  stroke?: string;
  fill?: string;
  strokeWidth?: number;
  className?: string;
  animate?: boolean;
}

export function Sparkline({
  values,
  width = 160,
  height = 44,
  stroke = "var(--gold)",
  fill = "rgba(212,175,55,0.12)",
  strokeWidth = 1.5,
  className,
  animate = true,
}: SparklineProps) {
  const pathRef = useRef<SVGPathElement | null>(null);
  const [length, setLength] = useState(0);

  useEffect(() => {
    if (!animate) return;
    if (pathRef.current) {
      setLength(pathRef.current.getTotalLength());
    }
  }, [animate, values]);

  if (!values || values.length === 0) {
    return (
      <svg width={width} height={height} className={className} aria-hidden>
        <line
          x1={0}
          y1={height / 2}
          x2={width}
          y2={height / 2}
          stroke="var(--hairline)"
          strokeDasharray="2 3"
        />
      </svg>
    );
  }

  const min = Math.min(...values);
  const max = Math.max(...values);
  const range = max - min || 1;
  const stepX = values.length > 1 ? width / (values.length - 1) : width;

  const points = values.map((v, i) => {
    const x = i * stepX;
    const y = height - ((v - min) / range) * (height - 4) - 2;
    return [x, y] as const;
  });

  const linePath = points.reduce(
    (acc, [x, y], i) => acc + (i === 0 ? `M${x},${y}` : ` L${x},${y}`),
    ""
  );
  const areaPath = `${linePath} L${width},${height} L0,${height} Z`;

  return (
    <svg
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      className={className}
      aria-hidden
    >
      <path d={areaPath} fill={fill} />
      <path
        ref={pathRef}
        d={linePath}
        fill="none"
        stroke={stroke}
        strokeWidth={strokeWidth}
        strokeLinecap="round"
        strokeLinejoin="round"
        style={
          animate && length > 0
            ? {
                strokeDasharray: length,
                strokeDashoffset: length,
                animation: "mc-spark-draw 700ms ease-out forwards",
              }
            : undefined
        }
      />
      <style>{`
        @keyframes mc-spark-draw {
          to { stroke-dashoffset: 0; }
        }
      `}</style>
    </svg>
  );
}
