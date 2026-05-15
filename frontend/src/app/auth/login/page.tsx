"use client";

import { Suspense } from "react";
import { CinematicHeader } from "./_components/CinematicHeader";
import { CinematicBackdrop } from "./_components/CinematicBackdrop";
import { CinematicForm } from "./_components/CinematicForm";
import { CinematicKeyframes } from "./_components/CinematicKeyframes";

/**
 * Bipros EPPM sign-in — cinematic site-of-record concept.
 *
 * Layout: dark header bar across the top, then a two-column grid where the
 * left column is the cinematic backdrop (sky + cranes + editorial overlay)
 * and the right column is the glass auth card. Collapses to a single column
 * on mobile, with the backdrop above and the form below on a darker scrim.
 *
 * The form lives inside a Suspense boundary because useLoginSubmit calls
 * useSearchParams() to read `?next=`, which Next.js 16 requires to be
 * wrapped in Suspense for client-side routing primitives.
 */
export default function LoginPage() {
  return (
    <div className="relative min-h-screen overflow-hidden bg-[#0B1224] font-sans text-[#EDEDEA] antialiased">
      <CinematicHeader />
      <main className="relative grid min-h-[calc(100vh-72px)] grid-cols-1 lg:grid-cols-[1.5fr_1fr]">
        <CinematicBackdrop />
        <Suspense
          fallback={
            <div className="grid place-items-center px-6 py-12 text-[12px] text-white/50 lg:px-12">
              Loading…
            </div>
          }
        >
          <CinematicForm />
        </Suspense>
      </main>
      <CinematicKeyframes />
    </div>
  );
}
