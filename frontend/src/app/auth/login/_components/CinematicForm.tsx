"use client";

import Link from "next/link";
import { ArrowUpRight, Eye, EyeOff, Lock, ShieldCheck } from "lucide-react";
import { useLoginSubmit } from "../../login-samples/_shared/useLoginSubmit";

/**
 * Cinematic sign-in card. Glass surface on top of the cinematic backdrop on
 * lg+, opaque scrim on mobile. Uses the shared useLoginSubmit hook (same
 * auth flow as every other sample in /auth/login-samples/*).
 *
 * No SSO row and no "Forgot password" link — the backend has no SSO
 * endpoints or password-reset flow today. Re-add when those are real.
 */
export function CinematicForm() {
  const f = useLoginSubmit();
  return (
    <aside className="relative flex items-center justify-center px-6 py-10 lg:px-10 lg:py-16">
      {/* Scrim behind the card so it stays readable on top of the photo on mobile */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 lg:hidden"
        style={{
          background:
            "linear-gradient(180deg,rgba(11,18,36,0.92),rgba(11,18,36,0.97))",
        }}
      />
      <div className="cn-rise cn-d2 relative w-full max-w-[420px]">
        <div
          aria-hidden
          className="pointer-events-none absolute inset-x-7 top-0 h-px"
          style={{
            background:
              "linear-gradient(90deg,transparent,#F4B36A,transparent)",
          }}
        />
        <form
          onSubmit={f.handleSubmit}
          noValidate
          className="relative rounded-2xl border border-white/[0.10] bg-[#0B1224]/75 p-7 shadow-[0_40px_90px_rgba(0,0,0,0.55)] backdrop-blur-xl"
        >
          <div className="flex items-start justify-between">
            <div>
              <div className="flex items-center gap-2 text-[10.5px] uppercase tracking-[0.22em] text-[#F4B36A]">
                <span aria-hidden className="inline-block h-px w-5 bg-[#F4B36A]" />
                Sign in
              </div>
              <h2 className="mt-2 text-[28px] font-medium leading-[1.05] tracking-[-0.015em] text-white" style={{ fontVariationSettings: "'opsz' 144" }}>
                Welcome back.
              </h2>
              <p className="mt-1 text-[13px] text-white/60">Sign in to your operational layer.</p>
            </div>
            <span className="flex items-center gap-1 rounded border border-white/[0.10] bg-white/[0.04] px-1.5 py-1 font-mono text-[9px] uppercase tracking-[0.18em] text-white/65">
              <Lock size={10} /> TLS
            </span>
          </div>

          <div className="mt-6 space-y-4">
            <div>
              <label htmlFor="cn-user" className="mb-1.5 block text-[10.5px] uppercase tracking-[0.16em] text-white/60">
                Username or email
              </label>
              <input
                id="cn-user"
                type="text"
                autoComplete="username"
                autoFocus
                required
                value={f.username}
                onChange={(e) => f.setUsername(e.target.value)}
                disabled={f.submitting}
                placeholder="you@company.com"
                className="block h-11 w-full rounded-xl border border-white/[0.10] bg-black/30 px-3.5 text-[14px] text-white placeholder:text-white/30 transition focus:border-[#F4B36A]/60 focus:bg-black/50 focus:outline-none focus:ring-2 focus:ring-[#F4B36A]/25"
              />
            </div>
            <div>
              <label htmlFor="cn-pwd" className="mb-1.5 block text-[10.5px] uppercase tracking-[0.16em] text-white/60">
                Password
              </label>
              <div className="relative">
                <input
                  id="cn-pwd"
                  type={f.showPassword ? "text" : "password"}
                  autoComplete="current-password"
                  required
                  value={f.password}
                  onChange={(e) => f.setPassword(e.target.value)}
                  disabled={f.submitting}
                  placeholder="••••••••"
                  className="block h-11 w-full rounded-xl border border-white/[0.10] bg-black/30 px-3.5 pr-10 text-[14px] text-white placeholder:text-white/30 transition focus:border-[#F4B36A]/60 focus:bg-black/50 focus:outline-none focus:ring-2 focus:ring-[#F4B36A]/25"
                />
                <button
                  type="button"
                  onClick={() => f.setShowPassword((v) => !v)}
                  className="absolute inset-y-0 right-0 flex items-center px-3 text-white/50 hover:text-[#F4B36A]"
                  aria-label={f.showPassword ? "Hide password" : "Show password"}
                  tabIndex={-1}
                >
                  {f.showPassword ? <EyeOff size={15} /> : <Eye size={15} />}
                </button>
              </div>
            </div>
          </div>

          <label className="mt-3.5 flex cursor-pointer items-center gap-2 text-[12.5px] text-white/70">
            <input
              type="checkbox"
              checked={f.remember}
              onChange={(e) => f.setRemember(e.target.checked)}
              className="h-3.5 w-3.5 rounded border-white/30 accent-[#F4B36A]"
            />
            Keep me signed in for 7 days
          </label>

          {f.fieldError && (
            <div role="alert" className="mt-4 rounded-xl border border-red-500/30 bg-red-500/10 px-3.5 py-2.5 text-[13px] text-red-200">
              {f.fieldError}
            </div>
          )}

          <button
            type="submit"
            disabled={f.submitting}
            className="group relative mt-5 inline-flex h-12 w-full items-center justify-center gap-2 overflow-hidden rounded-xl text-[14.5px] font-semibold text-[#0B1224] transition disabled:opacity-60"
            style={{ background: "linear-gradient(180deg,#FFC988,#F4B36A 55%,#C97A3A)", boxShadow: "0 18px 40px rgba(244,179,106,0.28)" }}
          >
            {f.submitting ? (
              <>
                <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-black/30 border-t-black" />
                Signing in…
              </>
            ) : (
              <>
                Sign in
                <ArrowUpRight size={15} className="transition group-hover:-translate-y-0.5 group-hover:translate-x-0.5" />
              </>
            )}
          </button>

          <div className="mt-6 flex items-center justify-between border-t border-white/[0.08] pt-4">
            <span className="flex items-center gap-1.5 text-[11.5px] text-white/60">
              <ShieldCheck size={12} className="text-[#F4B36A]" />
              JWT-bound · end-to-end
            </span>
            <div className="flex flex-wrap justify-end gap-1">
              {["SOC 2", "ISO 27001", "GDPR"].map((b) => (
                <span key={b} className="rounded border border-white/[0.10] bg-white/[0.04] px-1.5 py-0.5 font-mono text-[8.5px] uppercase tracking-[0.14em] text-white/60">
                  {b}
                </span>
              ))}
            </div>
          </div>
          <p className="mt-4 text-center text-[12.5px] text-white/60">
            New here?{" "}
            <Link href="/welcome" className="font-medium text-[#F4B36A] hover:text-white">Take the tour →</Link>
          </p>
        </form>
      </div>
    </aside>
  );
}
