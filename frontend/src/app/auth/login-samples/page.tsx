"use client";

import Link from "next/link";
import { ArrowUpRight } from "lucide-react";

const SAMPLES = [
  {
    href: "/auth/login-samples/executive",
    n: "01",
    title: "Executive Enterprise",
    sub: "Oracle · SAP · Palantir lineage",
    desc: "Dense operational telemetry on a deep-slate canvas. Restrained gold, monospaced data, programme command surface.",
    palette: ["#0E1320", "#0A0E18", "#D4AF37", "#E5E9F2"],
  },
  {
    href: "/auth/login-samples/minimal",
    n: "02",
    title: "Modern Minimal Enterprise",
    sub: "Linear · Stripe rhythm",
    desc: "Centered narrow column. Near-monochrome, surgical hierarchy. Minimal friction, maximal clarity.",
    palette: ["#FAFAFA", "#FFFFFF", "#0A0A0A", "#10B981"],
  },
  {
    href: "/auth/login-samples/industrial",
    n: "03",
    title: "Industrial Operations Console",
    sub: "Construction · SCADA · HMI mood",
    desc: "Project command board with live site tiles, safety yellow + signal green, hard edges, monospace dominance.",
    palette: ["#13161A", "#0B0D10", "#FFC600", "#3DDC84"],
  },
  {
    href: "/auth/login-samples/ai",
    n: "04",
    title: "AI-Powered Programme Intelligence",
    sub: "AI-native enterprise UX",
    desc: "Live insights feed previews the post-login value. Restrained gradient mesh, indigo + cyan, glass auth card.",
    palette: ["#0B0E1A", "#6366F1", "#22D3EE", "#FFFFFF"],
  },
  {
    href: "/auth/login-samples/dark",
    n: "05",
    title: "Premium Dark Enterprise",
    sub: "Refined dark · editorial confidence",
    desc: "Deep layered blacks with one champagne accent. Generous spacing, serif display, pristine readability.",
    palette: ["#0A0A0B", "#0E0E10", "#E8C67B", "#EDEDEA"],
  },
  {
    href: "/auth/login-samples/aerial",
    n: "07",
    title: "Aerial Programme View",
    sub: "Topographic · tracking station",
    desc: "Cartographic map with contour lines, status pins and great-circle arcs. Tracking-station auth card pinned bottom-right.",
    palette: ["#0F1A18", "#1F2421", "#C8B273", "#5EBFD4"],
  },
  {
    href: "/auth/login-samples/blueprint",
    n: "08",
    title: "Drawing Office · Engineering Blueprint",
    sub: "Cyanotype · drafting heritage",
    desc: "Working SVG blueprint — orthographic elevation, plan view, dimension callouts and a real title block. Stamp-card auth.",
    palette: ["#0E2438", "#9DC3D8", "#E8F1F7", "#A23B2A"],
  },
  {
    href: "/auth/login-samples/orbital",
    n: "09",
    title: "Operations Bridge · Global Network",
    sub: "World map · 12 city pins",
    desc: "Stylised dot-map of continents with status-coloured city pins and dashed great-circle arcs. EU/US/APAC latency badges.",
    palette: ["#0B1020", "#1A2434", "#5EBFD4", "#F0B454"],
  },
  {
    href: "/auth/login-samples/editorial",
    n: "10",
    title: "Atrium · Editorial Daylight",
    sub: "Cream · serif · radiant",
    desc: "Photographic daylight atrium with sun-beams and glass-facade silhouettes. Centred italic-serif headline, cream-on-cream card.",
    palette: ["#F5F1EA", "#E8D9B8", "#D8DCDF", "#1A1F2C"],
  },
];

export default function LoginSamplesIndex() {
  return (
    <div className="min-h-screen bg-neutral-950 font-sans text-neutral-200 antialiased">
      <header className="border-b border-white/[0.06] px-6 py-6 lg:px-12">
        <div className="mx-auto flex max-w-6xl items-center justify-between">
          <div className="flex items-center gap-3">
            <span className="grid h-7 w-7 place-items-center rounded-md bg-amber-300 text-[12px] font-bold text-black">B</span>
            <div className="leading-tight">
              <div className="text-[14px] font-semibold tracking-[-0.01em] text-white">Bipros EPPM</div>
              <div className="text-[10.5px] uppercase tracking-[0.20em] text-white/50">Login concepts · 10 directions</div>
            </div>
          </div>
          <Link
            href="/auth/login"
            className="text-[12.5px] text-white/60 underline-offset-4 hover:text-white hover:underline"
          >
            ← Current login
          </Link>
        </div>
      </header>

      <main className="mx-auto max-w-6xl px-6 pb-20 pt-12 lg:px-12">
        <div className="mb-10 max-w-2xl">
          <div className="mb-3 text-[10.5px] uppercase tracking-[0.24em] text-amber-300">Ten concepts</div>
          <h1 className="text-[40px] font-semibold leading-[1.05] tracking-[-0.025em] text-white sm:text-[44px]">
            Pick a direction.
          </h1>
          <p className="mt-3 text-[14px] leading-[1.65] text-white/65">
            Each concept is a fully working sign-in surface — wired to the real
            auth flow. Click a card to preview at full fidelity. Pixel-level
            tuning, motion polish, and tablet/mobile layouts are tightened once
            you commit to one direction.
          </p>
        </div>

        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          {SAMPLES.map((s) => (
            <Link
              key={s.href}
              href={s.href}
              className="group relative overflow-hidden rounded-2xl border border-white/[0.08] bg-white/[0.02] p-6 transition hover:-translate-y-0.5 hover:border-amber-300/30 hover:bg-white/[0.045]"
            >
              <div className="flex items-start justify-between">
                <div className="flex items-center gap-3">
                  <span className="font-mono text-[10.5px] uppercase tracking-[0.20em] text-white/40">{s.n}</span>
                  <span className="font-mono text-[10.5px] uppercase tracking-[0.16em] text-amber-300">{s.sub}</span>
                </div>
                <ArrowUpRight size={16} className="text-white/40 transition group-hover:-translate-y-0.5 group-hover:translate-x-0.5 group-hover:text-amber-300" />
              </div>
              <div className="mt-3 text-[20px] font-semibold tracking-[-0.015em] text-white">{s.title}</div>
              <p className="mt-2 max-w-md text-[13.5px] leading-[1.6] text-white/60">{s.desc}</p>
              <div className="mt-5 flex items-center gap-1.5">
                {s.palette.map((c) => (
                  <span
                    key={c}
                    className="h-5 w-9 rounded-sm border border-white/10"
                    style={{ background: c }}
                    aria-hidden
                  />
                ))}
                <span className="ml-2 font-mono text-[10px] uppercase tracking-[0.14em] text-white/35">
                  Open preview →
                </span>
              </div>
            </Link>
          ))}
        </div>

        <div className="mt-14 rounded-2xl border border-white/[0.06] bg-white/[0.02] p-6 text-[13.5px] leading-[1.6] text-white/65">
          <div className="mb-1 text-[10.5px] uppercase tracking-[0.20em] text-white/45">Notes</div>
          <ul className="list-disc space-y-1 pl-5 marker:text-amber-300/70">
            <li>All five share the existing auth flow (cookie, JWT prime, <code className="font-mono text-[12px] text-white/80">/v1/users/me</code>, store hydration, hard nav).</li>
            <li>Animations are CSS-only for now to avoid pulling in framer-motion before commitment — easy to upgrade once a direction is picked.</li>
            <li>Responsive variants (tablet 768–1024, mobile &lt; 768) collapse the dual-pane layouts into single-column auth-first ordering.</li>
            <li>Every concept ships with its own SSO row, error state, password show/hide, remember-me, and trust-mark footer. Empty states default to gentle copy; loading states use a 14px ring spinner inside the primary CTA.</li>
          </ul>
        </div>
      </main>
    </div>
  );
}
