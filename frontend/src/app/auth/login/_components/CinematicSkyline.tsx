"use client";

/**
 * The inline-SVG cinematic backdrop: distant skyline strip, half-built tower
 * with ribbed steel skeleton and warm interior lights, five tower cranes at
 * varied heights, and a single warm lamp on the central crane.
 *
 * Pure presentational SVG — no client state, no dynamic positioning.
 */
export function CinematicSkyline() {
  return (
    <svg aria-hidden viewBox="0 0 1600 1000" preserveAspectRatio="xMidYMax slice" className="absolute inset-0 h-full w-full">
      <defs>
        <linearGradient id="cn-tower" x1="0" x2="0" y1="0" y2="1">
          <stop offset="0" stopColor="#0B1224" stopOpacity="0.1" />
          <stop offset="1" stopColor="#050810" stopOpacity="0.95" />
        </linearGradient>
        <linearGradient id="cn-city" x1="0" x2="0" y1="0" y2="1">
          <stop offset="0" stopColor="#1B2238" stopOpacity="0.85" />
          <stop offset="1" stopColor="#070B17" stopOpacity="1" />
        </linearGradient>
        <radialGradient id="cn-lamp" cx="0.5" cy="0.5" r="0.5">
          <stop offset="0" stopColor="#FFE3B0" stopOpacity="1" />
          <stop offset="0.4" stopColor="#FFB35E" stopOpacity="0.55" />
          <stop offset="1" stopColor="#FFB35E" stopOpacity="0" />
        </radialGradient>
      </defs>

      {/* Distant city skyline strip */}
      <path
        fill="url(#cn-city)"
        d="M0,720 L0,820 L60,820 L60,760 L120,760 L120,790 L180,790 L180,740 L240,740 L240,800 L300,800 L300,760 L360,760 L360,810 L420,810 L420,770 L500,770 L500,800 L560,800 L560,750 L620,750 L620,790 L700,790 L700,760 L780,760 L780,810 L840,810 L840,775 L920,775 L920,800 L1000,800 L1000,755 L1080,755 L1080,790 L1160,790 L1160,770 L1240,770 L1240,810 L1320,810 L1320,775 L1400,775 L1400,800 L1480,800 L1480,765 L1600,765 L1600,820 Z"
      />

      {/* Crane #1 — far left, smallest */}
      <g stroke="#070B17" strokeWidth="2" fill="#070B17" opacity="0.85">
        <rect x="138" y="380" width="6" height="360" />
        <rect x="60" y="372" width="240" height="4" />
        <rect x="40" y="378" width="60" height="6" />
        <rect x="124" y="368" width="22" height="14" />
        <line x1="220" y1="376" x2="220" y2="500" strokeWidth="1.5" />
        <rect x="216" y="500" width="8" height="6" />
      </g>

      {/* Half-built tower — ribbed steel skeleton */}
      <rect x="420" y="300" width="220" height="440" fill="#050810" opacity="0.92" />
      <g stroke="#2A3550" strokeWidth="1.2" opacity="0.85">
        {[340, 380, 420, 460, 500, 540, 580, 620, 660, 700].map((y) => (
          <line key={`fp-${y}`} x1="420" y1={y} x2="640" y2={y} />
        ))}
        {[450, 490, 530, 570, 610].map((x) => (
          <line key={`col-${x}`} x1={x} y1="300" x2={x} y2="740" />
        ))}
      </g>
      {/* warm interior lights */}
      <g fill="#FFB35E" opacity="0.75">
        <rect x="494" y="424" width="6" height="6" />
        <rect x="574" y="464" width="6" height="6" />
        <rect x="454" y="544" width="6" height="6" />
        <rect x="614" y="624" width="6" height="6" />
        <rect x="534" y="684" width="6" height="6" />
      </g>
      <rect x="420" y="296" width="220" height="6" fill="#0B1224" />
      <rect x="420" y="290" width="120" height="8" fill="#0B1224" />

      {/* Crane #2 — tallest, centre, atop the tower */}
      <g stroke="#040711" strokeWidth="2.5" fill="#040711">
        <rect x="524" y="80" width="8" height="220" />
        <rect x="380" y="74" width="320" height="5" />
        <rect x="360" y="80" width="80" height="8" />
        <rect x="510" y="68" width="26" height="16" />
        <line x1="528" y1="60" x2="700" y2="79" strokeWidth="1.5" stroke="#1B2238" />
        <line x1="528" y1="60" x2="360" y2="80" strokeWidth="1.5" stroke="#1B2238" />
        <rect x="525" y="56" width="6" height="10" />
        <line x1="640" y1="79" x2="640" y2="240" strokeWidth="1.5" />
        <rect x="634" y="240" width="12" height="8" />
      </g>
      {/* Working crane lamp — single point of light */}
      <circle cx="640" cy="244" r="42" fill="url(#cn-lamp)" />
      <circle cx="640" cy="244" r="3" fill="#FFE3B0" />

      {/* Crane #3 — mid-right, medium height */}
      <g stroke="#050810" strokeWidth="2" fill="#050810" opacity="0.92">
        <rect x="980" y="240" width="7" height="500" />
        <rect x="850" y="232" width="280" height="4" />
        <rect x="824" y="238" width="60" height="7" />
        <rect x="966" y="226" width="24" height="14" />
        <line x1="1060" y1="236" x2="1060" y2="380" strokeWidth="1.5" />
        <rect x="1054" y="380" width="12" height="7" />
      </g>

      {/* Crane #4 — far right, second tallest */}
      <g stroke="#040711" strokeWidth="2.5" fill="#040711">
        <rect x="1320" y="160" width="8" height="580" />
        <rect x="1180" y="152" width="320" height="5" />
        <rect x="1158" y="158" width="68" height="8" />
        <rect x="1308" y="146" width="26" height="16" />
        <line x1="1324" y1="138" x2="1500" y2="157" strokeWidth="1.5" stroke="#1B2238" />
        <line x1="1324" y1="138" x2="1158" y2="158" strokeWidth="1.5" stroke="#1B2238" />
        <rect x="1321" y="134" width="6" height="10" />
        <line x1="1400" y1="157" x2="1400" y2="320" strokeWidth="1.5" />
        <rect x="1394" y="320" width="12" height="8" />
      </g>

      {/* Crane #5 — short utility crane */}
      <g stroke="#050810" strokeWidth="2" fill="#050810" opacity="0.85">
        <rect x="1148" y="450" width="5" height="290" />
        <rect x="1060" y="444" width="180" height="3" />
        <rect x="1042" y="448" width="46" height="5" />
        <rect x="1138" y="438" width="20" height="12" />
        <line x1="1200" y1="447" x2="1200" y2="540" strokeWidth="1.2" />
        <rect x="1196" y="540" width="8" height="5" />
      </g>

      {/* Foreground ground */}
      <rect x="0" y="740" width="1600" height="260" fill="url(#cn-tower)" />
      <rect x="0" y="740" width="1600" height="2" fill="#040711" />

      {/* Distant specs */}
      <circle cx="1080" cy="120" r="1.4" fill="#FFE3B0" opacity="0.7" />
      <circle cx="280" cy="180" r="1" fill="#FFE3B0" opacity="0.5" />
    </svg>
  );
}
