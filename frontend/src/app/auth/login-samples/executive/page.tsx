"use client";

import { Suspense } from "react";
import Link from "next/link";
import { Eye, EyeOff, Lock, ShieldCheck, ArrowRight, Activity, Server, Building2 } from "lucide-react";
import { useLoginSubmit } from "../_shared/useLoginSubmit";

/**
 * Concept 1 — Executive Enterprise.
 * Oracle / SAP / Palantir lineage: dense operational telemetry,
 * deep slate canvas, restrained gold, monospace data labels.
 */
export default function ExecutivePage() {
  return (
    <div className="min-h-screen bg-[#0E1320] font-sans text-[#E5E9F2] antialiased">
      <SystemBar />
      <main className="grid min-h-[calc(100vh-32px)] grid-cols-1 lg:grid-cols-[1.35fr_minmax(420px,460px)]">
        <Telemetry />
        <Suspense fallback={<div className="grid place-items-center text-[12px] text-white/40">Loading sign-in…</div>}>
          <AuthPanel />
        </Suspense>
      </main>
      <Keyframes />
    </div>
  );
}

function SystemBar() {
  return (
    <div className="flex h-8 items-center justify-between border-b border-white/[0.06] bg-[#0A0E18] px-4 font-mono text-[10.5px] uppercase tracking-[0.18em] text-white/55">
      <div className="flex items-center gap-4">
        <span className="flex items-center gap-1.5 text-[#D4AF37]">
          <span className="grid h-4 w-4 place-items-center rounded-[3px] bg-[#D4AF37] text-[10px] font-bold text-[#0A0E18]">B</span>
          Bipros · EPPM
        </span>
        <span className="opacity-60">Region: EU-WEST-2</span>
        <span className="opacity-60">Tenant: ACME-CORP</span>
        <span className="opacity-60">Build 2026.05.1</span>
      </div>
      <div className="flex items-center gap-4">
        <span className="flex items-center gap-1.5"><span className="h-1.5 w-1.5 animate-pulse rounded-full bg-emerald-400" />API · 99.99%</span>
        <span className="opacity-60">14:32:08 UTC</span>
      </div>
    </div>
  );
}

function Telemetry() {
  return (
    <section className="relative overflow-hidden border-r border-white/[0.05] bg-[#0E1320] px-8 py-10 lg:px-12 lg:py-14">
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 opacity-[0.04]"
        style={{
          backgroundImage:
            "linear-gradient(#fff 1px,transparent 1px),linear-gradient(90deg,#fff 1px,transparent 1px)",
          backgroundSize: "44px 44px",
          maskImage: "radial-gradient(ellipse 90% 60% at 30% 30%,#000 30%,transparent 80%)",
          WebkitMaskImage: "radial-gradient(ellipse 90% 60% at 30% 30%,#000 30%,transparent 80%)",
        }}
      />
      <div className="relative mx-auto max-w-2xl">
        <div className="exec-rise mb-6 flex items-center gap-3 font-mono text-[10.5px] uppercase tracking-[0.24em] text-[#D4AF37]">
          <span className="inline-block h-px w-7 bg-[#D4AF37]" />
          Programme Operations Console
        </div>
        <h1 className="exec-rise exec-d1 max-w-xl text-[42px] font-medium leading-[1.05] tracking-[-0.02em] text-white">
          Enterprise project intelligence,
          <span className="text-[#D4AF37]"> consolidated.</span>
        </h1>
        <p className="exec-rise exec-d2 mt-5 max-w-lg text-[14px] leading-[1.65] text-white/65">
          Schedule, cost, resource, contract and risk on one governed surface.
          Sign in to access programme telemetry across your portfolio.
        </p>

        <div className="exec-rise exec-d3 mt-10 grid grid-cols-2 gap-3 sm:grid-cols-4">
          {KPIS.map((k) => (
            <div key={k.label} className="rounded-[6px] border border-white/[0.07] bg-white/[0.02] p-3.5">
              <div className="font-mono text-[9.5px] uppercase tracking-[0.18em] text-white/45">{k.label}</div>
              <div className="mt-1.5 font-display text-[24px] font-medium leading-none text-white tabular-nums" style={{ fontVariationSettings: "'opsz' 144" }}>
                {k.value}
              </div>
              <div className={`mt-1.5 font-mono text-[10px] tabular-nums ${k.tone}`}>{k.delta}</div>
            </div>
          ))}
        </div>

        <div className="exec-rise exec-d4 mt-10">
          <div className="mb-3 flex items-center justify-between font-mono text-[10px] uppercase tracking-[0.20em] text-white/50">
            <span>Active programmes · live</span>
            <span className="flex items-center gap-1.5"><span className="h-1.5 w-1.5 animate-pulse rounded-full bg-emerald-400" />Synced 14s ago</span>
          </div>
          <div className="overflow-hidden rounded-[6px] border border-white/[0.07] bg-white/[0.015]">
            <table className="w-full text-[12.5px]">
              <thead>
                <tr className="border-b border-white/[0.06] bg-white/[0.025] font-mono text-[9.5px] uppercase tracking-[0.16em] text-white/45">
                  <th className="px-3 py-2 text-left font-medium">Programme</th>
                  <th className="px-3 py-2 text-left font-medium">Phase</th>
                  <th className="px-3 py-2 text-right font-medium">% Comp</th>
                  <th className="px-3 py-2 text-right font-medium">Float</th>
                  <th className="px-3 py-2 text-left font-medium">Status</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-white/[0.05]">
                {PROGRAMMES.map((p) => (
                  <tr key={p.code} className="transition-colors hover:bg-white/[0.03]">
                    <td className="px-3 py-2.5">
                      <div className="font-medium text-white">{p.name}</div>
                      <div className="font-mono text-[10px] uppercase tracking-[0.12em] text-white/40">{p.code}</div>
                    </td>
                    <td className="px-3 py-2.5 font-mono text-[11px] text-white/70">{p.phase}</td>
                    <td className="px-3 py-2.5 text-right tabular-nums text-white/80">{p.pct}%</td>
                    <td className={`px-3 py-2.5 text-right font-mono tabular-nums ${p.float < 0 ? "text-red-400" : "text-white/70"}`}>{p.float}d</td>
                    <td className="px-3 py-2.5">
                      <StatusPill state={p.state} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        <div className="exec-rise exec-d5 mt-10 flex flex-wrap items-center gap-x-8 gap-y-3 border-t border-white/[0.06] pt-6 font-mono text-[10px] uppercase tracking-[0.18em] text-white/40">
          <span className="flex items-center gap-1.5"><Activity size={12} className="text-[#D4AF37]" /> 184,271 activities tracked</span>
          <span className="flex items-center gap-1.5"><Server size={12} className="text-[#D4AF37]" /> 12 environments · all healthy</span>
          <span className="flex items-center gap-1.5"><Building2 size={12} className="text-[#D4AF37]" /> 47 sites worldwide</span>
        </div>
      </div>
    </section>
  );
}

function AuthPanel() {
  const f = useLoginSubmit();
  return (
    <aside className="relative flex flex-col bg-[#0A0E18] px-8 py-10 lg:px-10 lg:py-14">
      <div
        aria-hidden
        className="absolute inset-x-0 top-0 h-px"
        style={{ background: "linear-gradient(90deg,transparent,#D4AF37,transparent)" }}
      />
      <div className="exec-rise mb-1 flex items-center justify-between font-mono text-[10px] uppercase tracking-[0.22em] text-white/45">
        <span>Authentication</span>
        <span className="flex items-center gap-1.5 text-emerald-400"><Lock size={11} /> TLS 1.3 · mTLS</span>
      </div>
      <h2 className="exec-rise exec-d1 mt-3 font-display text-[30px] font-medium leading-[1.05] tracking-[-0.015em] text-white" style={{ fontVariationSettings: "'opsz' 144" }}>
        Sign in to your tenant
      </h2>
      <p className="exec-rise exec-d2 mt-2 text-[13px] text-white/55">
        Use a corporate identity. Local accounts are reserved for break-glass operations.
      </p>

      <form onSubmit={f.handleSubmit} noValidate className="exec-rise exec-d3 mt-8 space-y-4">
        <div className="grid grid-cols-1 gap-2">
          <SsoRow label="Continue with Microsoft Entra ID" badge="Recommended" />
          <SsoRow label="Continue with Okta SAML" />
          <SsoRow label="Continue with Google Workspace" />
        </div>

        <div className="my-5 flex items-center gap-3 font-mono text-[9.5px] uppercase tracking-[0.20em] text-white/40">
          <div className="h-px flex-1 bg-white/[0.07]" />
          Local credentials
          <div className="h-px flex-1 bg-white/[0.07]" />
        </div>

        <Field label="Corporate Email / Username" htmlFor="exec-user">
          <input
            id="exec-user"
            type="text"
            autoComplete="username"
            autoFocus
            required
            value={f.username}
            onChange={(e) => f.setUsername(e.target.value)}
            disabled={f.submitting}
            placeholder="firstname.lastname@acme.com"
            className="block h-11 w-full rounded-[5px] border border-white/[0.08] bg-white/[0.025] px-3.5 text-[13.5px] text-white placeholder:text-white/30 transition focus:border-[#D4AF37] focus:bg-white/[0.04] focus:outline-none focus:ring-2 focus:ring-[#D4AF37]/25"
          />
        </Field>

        <Field
          label="Password"
          htmlFor="exec-pwd"
          right={
            <button type="button" onClick={() => {}} className="font-mono text-[10px] uppercase tracking-[0.16em] text-[#D4AF37] hover:text-white">
              Reset
            </button>
          }
        >
          <div className="relative">
            <input
              id="exec-pwd"
              type={f.showPassword ? "text" : "password"}
              autoComplete="current-password"
              required
              value={f.password}
              onChange={(e) => f.setPassword(e.target.value)}
              disabled={f.submitting}
              placeholder="••••••••••••"
              className="block h-11 w-full rounded-[5px] border border-white/[0.08] bg-white/[0.025] px-3.5 pr-10 text-[13.5px] text-white placeholder:text-white/30 transition focus:border-[#D4AF37] focus:bg-white/[0.04] focus:outline-none focus:ring-2 focus:ring-[#D4AF37]/25"
            />
            <button
              type="button"
              onClick={() => f.setShowPassword((v) => !v)}
              aria-label={f.showPassword ? "Hide password" : "Show password"}
              className="absolute inset-y-0 right-0 flex items-center px-3 text-white/50 hover:text-[#D4AF37]"
              tabIndex={-1}
            >
              {f.showPassword ? <EyeOff size={15} /> : <Eye size={15} />}
            </button>
          </div>
        </Field>

        <label className="flex cursor-pointer items-center gap-2 text-[12px] text-white/55">
          <input
            type="checkbox"
            checked={f.remember}
            onChange={(e) => f.setRemember(e.target.checked)}
            className="h-3.5 w-3.5 rounded-sm border-white/30 bg-transparent accent-[#D4AF37]"
          />
          Trust this device for 8 hours
        </label>

        {f.fieldError && (
          <div role="alert" className="rounded-[5px] border border-red-500/30 bg-red-500/10 px-3 py-2.5 text-[12.5px] text-red-300">
            {f.fieldError}
          </div>
        )}

        <button
          type="submit"
          disabled={f.submitting}
          className="group relative mt-2 inline-flex h-11 w-full items-center justify-center gap-2 rounded-[5px] bg-[#D4AF37] text-[13.5px] font-semibold tracking-[0.01em] text-[#0A0E18] shadow-[0_8px_24px_rgba(0,88,202,0.25)] transition hover:bg-[#E6BF42] disabled:opacity-60"
        >
          {f.submitting ? (
            <>
              <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-[#0A0E18]/30 border-t-[#0A0E18]" />
              Authenticating
            </>
          ) : (
            <>
              Authenticate
              <ArrowRight size={15} className="transition group-hover:translate-x-0.5" />
            </>
          )}
        </button>
      </form>

      <div className="mt-auto pt-10">
        <div className="flex items-center justify-between border-t border-white/[0.06] pt-5 font-mono text-[9.5px] uppercase tracking-[0.18em] text-white/40">
          <span className="flex items-center gap-1.5"><ShieldCheck size={11} className="text-[#D4AF37]" /> JWT · RS256</span>
          <div className="flex gap-1.5">
            {["SOC 2 II", "ISO 27001", "GDPR", "HIPAA"].map((b) => (
              <span key={b} className="rounded-[3px] border border-white/[0.10] px-1.5 py-0.5">{b}</span>
            ))}
          </div>
        </div>
        <p className="mt-3 text-[11px] text-white/40">
          Need access?{" "}
          <Link href="/welcome" className="text-[#D4AF37] hover:text-white">Contact your tenant administrator</Link>
        </p>
      </div>
    </aside>
  );
}

function SsoRow({ label, badge }: { label: string; badge?: string }) {
  return (
    <button
      type="button"
      className="group flex h-11 w-full items-center justify-between rounded-[5px] border border-white/[0.08] bg-white/[0.025] px-3.5 text-[13px] font-medium text-white/85 transition hover:border-[#D4AF37]/50 hover:bg-white/[0.05]"
    >
      <span>{label}</span>
      <span className="flex items-center gap-2">
        {badge && <span className="rounded-[3px] border border-[#D4AF37]/40 bg-[#D4AF37]/10 px-1.5 py-0.5 font-mono text-[9px] uppercase tracking-[0.14em] text-[#D4AF37]">{badge}</span>}
        <ArrowRight size={13} className="text-white/40 transition group-hover:translate-x-0.5 group-hover:text-[#D4AF37]" />
      </span>
    </button>
  );
}

function Field({
  label,
  htmlFor,
  right,
  children,
}: {
  label: string;
  htmlFor: string;
  right?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <div>
      <div className="mb-1.5 flex items-baseline justify-between">
        <label htmlFor={htmlFor} className="font-mono text-[10px] uppercase tracking-[0.18em] text-white/55">
          {label}
        </label>
        {right}
      </div>
      {children}
    </div>
  );
}

function StatusPill({ state }: { state: "ON_TRACK" | "AT_RISK" | "OVERRUN" }) {
  const cfg = {
    ON_TRACK: { label: "On track", cls: "border-emerald-400/30 bg-emerald-400/10 text-emerald-300", dot: "bg-emerald-400" },
    AT_RISK: { label: "At risk", cls: "border-amber-400/30 bg-amber-400/10 text-amber-300", dot: "bg-amber-400" },
    OVERRUN: { label: "Overrun", cls: "border-red-400/30 bg-red-400/10 text-red-300", dot: "bg-red-400" },
  }[state];
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-[3px] border px-1.5 py-0.5 font-mono text-[9.5px] uppercase tracking-[0.14em] ${cfg.cls}`}>
      <span className={`h-1.5 w-1.5 rounded-full ${cfg.dot}`} />
      {cfg.label}
    </span>
  );
}

const KPIS: Array<{ label: string; value: string; delta: string; tone: string }> = [
  { label: "Active prog.", value: "12", delta: "+2 QoQ", tone: "text-emerald-400" },
  { label: "At-risk", value: "2", delta: "−1 vs L4W", tone: "text-emerald-400" },
  { label: "SPI (port.)", value: "1.04", delta: "+0.02", tone: "text-emerald-400" },
  { label: "CPI (port.)", value: "0.98", delta: "−0.01", tone: "text-amber-400" },
];

const PROGRAMMES: Array<{ code: string; name: string; phase: string; pct: number; float: number; state: "ON_TRACK" | "AT_RISK" | "OVERRUN" }> = [
  { code: "RAIL-SCR", name: "Sutton Coldfield Rail Corridor", phase: "Construction", pct: 62, float: 4, state: "ON_TRACK" },
  { code: "WND-NS3", name: "North Sea Cluster III · Offshore", phase: "Procurement", pct: 28, float: -2, state: "AT_RISK" },
  { code: "DC-FRA02", name: "Frankfurt Hyperscale DC · Phase 2", phase: "Commissioning", pct: 91, float: 6, state: "ON_TRACK" },
  { code: "HSR-LYM", name: "Lyon–Marseille HSR Extension", phase: "Design", pct: 14, float: 0, state: "AT_RISK" },
  { code: "PWR-SUB", name: "Substation Modernisation · UK", phase: "Construction", pct: 47, float: -8, state: "OVERRUN" },
];

function Keyframes() {
  return (
    <style>{`
      @keyframes execRise { from { opacity:0; transform: translateY(10px); } to { opacity:1; transform:none; } }
      .exec-rise { animation: execRise .6s cubic-bezier(.22,1,.36,1) both; }
      .exec-d1 { animation-delay: .06s; }
      .exec-d2 { animation-delay: .12s; }
      .exec-d3 { animation-delay: .20s; }
      .exec-d4 { animation-delay: .30s; }
      .exec-d5 { animation-delay: .42s; }
      @media (prefers-reduced-motion: reduce) { .exec-rise { animation: none !important; } }
    `}</style>
  );
}
