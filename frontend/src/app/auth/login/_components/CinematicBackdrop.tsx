"use client";

import { CinematicSkyline } from "./CinematicSkyline";
import { CinematicEditorial } from "./CinematicEditorial";

/**
 * Stacks all visual layers behind the sign-in form: sky gradient, sun glow,
 * dust haze, the SVG skyline, a soft architectural grid, film grain, and a
 * right-edge vignette (lg+) so the auth card lands on a darker scrim.
 *
 * Visual ordering is significant — do not reorder without checking how each
 * layer composites against the cinematic mock.
 */
export function CinematicBackdrop() {
  return (
    <section className="relative isolate min-h-[60vh] overflow-hidden lg:min-h-full">
      {/* Sky gradient base */}
      <div aria-hidden className="absolute inset-0" style={{ background: "linear-gradient(180deg,#0B1224 0%,#16243F 42%,#3A2A1F 78%,#C97A3A 100%)" }} />
      {/* Soft sun glow on the horizon */}
      <div aria-hidden className="absolute inset-0" style={{ background: "radial-gradient(ellipse 50% 28% at 70% 78%, rgba(244,179,106,0.55), transparent 65%)" }} />
      {/* Dust haze across the lower half */}
      <div aria-hidden className="absolute inset-0" style={{ background: "radial-gradient(ellipse 90% 40% at 50% 95%, rgba(201,122,58,0.30), transparent 70%)" }} />

      {/* The construction scene */}
      <CinematicSkyline />

      {/* 1px grid overlay (architectural) */}
      <div
        aria-hidden
        className="absolute inset-0 opacity-[0.06]"
        style={{
          backgroundImage: "linear-gradient(#fff 1px,transparent 1px),linear-gradient(90deg,#fff 1px,transparent 1px)",
          backgroundSize: "64px 64px",
          maskImage: "radial-gradient(ellipse 80% 60% at 40% 30%,#000 20%,transparent 80%)",
          WebkitMaskImage: "radial-gradient(ellipse 80% 60% at 40% 30%,#000 20%,transparent 80%)",
        }}
      />

      {/* Film grain */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 opacity-[0.05] mix-blend-overlay"
        style={{
          backgroundImage:
            "url(\"data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='220' height='220'><filter id='n'><feTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='2' stitchTiles='stitch'/><feColorMatrix values='0 0 0 0 1 0 0 0 0 1 0 0 0 0 1 0 0 0 0.6 0'/></filter><rect width='100%' height='100%' filter='url(%23n)'/></svg>\")",
        }}
      />

      {/* Vignette right edge so the auth card lands on a darker scrim on lg */}
      <div aria-hidden className="pointer-events-none absolute inset-0 hidden lg:block" style={{ background: "linear-gradient(90deg,transparent 60%,rgba(11,18,36,0.55) 100%)" }} />

      <CinematicEditorial />
    </section>
  );
}
