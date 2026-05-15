"use client";

/**
 * Cinematic sign-in keyframes — the `.cn-rise` reveal cascade used by the
 * editorial column and the auth card. Honours prefers-reduced-motion.
 */
export function CinematicKeyframes() {
  return (
    <style>{`
      @keyframes cnRise { from { opacity:0; transform: translateY(12px); } to { opacity:1; transform:none; } }
      .cn-rise { animation: cnRise .7s cubic-bezier(.22,1,.36,1) both; }
      .cn-d1 { animation-delay: .05s; }
      .cn-d2 { animation-delay: .14s; }
      .cn-d3 { animation-delay: .24s; }
      .cn-d4 { animation-delay: .34s; }
      .cn-d5 { animation-delay: .46s; }
      @media (prefers-reduced-motion: reduce) { .cn-rise { animation: none !important; } }
    `}</style>
  );
}
