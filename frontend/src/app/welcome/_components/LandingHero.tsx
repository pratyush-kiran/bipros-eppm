"use client";

import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import toast from "react-hot-toast";
import { ArrowUpRight, Eye, EyeOff, Lock } from "lucide-react";
import { authApi } from "@/lib/api/authApi";
import { useAuthStore } from "@/lib/state/store";

type LiveStat = {
  label: string;
  value: string;
  delta: string;
  trend: "up" | "flat" | "down";
};

// PMS-flavoured metrics — programme/project counts, activity volume, resource deployment,
// schedule and risk performance. Deliberately no financial-portfolio framing.
const liveStats: LiveStat[] = [
  { label: "Active programmes", value: "380", delta: "+12 QoQ", trend: "up" },
  { label: "Projects under management", value: "1,240", delta: "+78 QoQ", trend: "up" },
  { label: "Activities tracked", value: "184K", delta: "+9K MoM", trend: "up" },
  { label: "Resources deployed", value: "12.4K", delta: "+0.6K MoM", trend: "up" },
  { label: "Schedule adherence", value: "94.2%", delta: "+2.1 pts", trend: "up" },
  { label: "Critical risks resolved", value: "87%", delta: "vs. last cycle", trend: "flat" },
];

const trustedBy = ["Network Rail", "Ørsted", "Bechtel", "AECOM", "Skanska"];

export function LandingHero() {
  const router = useRouter();
  const { setAuth } = useAuthStore();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [remember, setRemember] = useState(true);
  const [showPw, setShowPw] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const ssoNotAvailable = () => toast("SSO sign-in is not yet configured.", { icon: "🔒" });

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);

    try {
      const result = await authApi.login({ username, password });
      if (result.data) {
        localStorage.setItem("access_token", result.data.accessToken);
        localStorage.setItem("refresh_token", result.data.refreshToken);
        document.cookie = `access_token=${result.data.accessToken}; path=/; max-age=3600`;
        const userResult = await authApi.me();
        if (userResult.data) {
          setAuth(userResult.data, result.data.accessToken, result.data.refreshToken);
          toast.success("Welcome back!");
          router.push("/");
        } else {
          setAuth(
            { id: "", username, email: "", firstName: "", lastName: "", enabled: true, roles: [] },
            result.data.accessToken,
            result.data.refreshToken,
          );
          toast.success("Logged in successfully!");
          router.push("/");
        }
      } else if (result.error) {
        const msg = result.error.message || "Login failed";
        setError(msg);
        toast.error(msg);
      }
    } catch {
      const msg = "Invalid username or password";
      setError(msg);
      toast.error(msg);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <section className="relative overflow-hidden">
      {/* Layered ambient background */}
      <div
        aria-hidden
        className="absolute inset-0"
        style={{ background: "linear-gradient(180deg,#FFFFFF 0%,#FAF9F6 55%,#F5F2E8 100%)" }}
      />
      <div
        aria-hidden
        className="absolute inset-0 opacity-[0.05]"
        style={{
          backgroundImage:
            "linear-gradient(#1C1C1C 1px,transparent 1px),linear-gradient(90deg,#1C1C1C 1px,transparent 1px)",
          backgroundSize: "56px 56px",
          maskImage: "radial-gradient(ellipse 80% 60% at 25% 35%,#000 30%,transparent 75%)",
          WebkitMaskImage: "radial-gradient(ellipse 80% 60% at 25% 35%,#000 30%,transparent 75%)",
        }}
      />
      <div
        aria-hidden
        className="pointer-events-none absolute -top-48 -right-40 h-[560px] w-[560px] rounded-full"
        style={{ background: "radial-gradient(circle, rgba(0,88,202,0.20) 0%, transparent 65%)" }}
      />
      <div
        aria-hidden
        className="pointer-events-none absolute top-[30%] -left-40 h-[360px] w-[360px] rounded-full"
        style={{ background: "radial-gradient(circle, rgba(0,88,202,0.08) 0%, transparent 70%)" }}
      />
      {/* Vertical gold thread between columns */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-y-10 hidden lg:block"
        style={{
          left: "calc(50% + 56px)",
          width: "1px",
          background:
            "linear-gradient(180deg, transparent 0%, rgba(0,88,202,0.30) 25%, rgba(0,88,202,0.30) 75%, transparent 100%)",
        }}
      />

      <div className="relative grid gap-10 px-9 pt-12 pb-8 lg:grid-cols-[1.25fr_1fr] lg:items-start lg:gap-14">
        {/* Left column */}
        <div className="relative">
          <div className="mb-4 flex flex-wrap items-center gap-3 text-[11px] font-semibold uppercase tracking-[0.22em] text-gold-deep">
            <span className="flex items-center gap-2">
              <span aria-hidden className="inline-block h-px w-6 bg-gold" />
              Enterprise PPM
            </span>
            <span className="flex items-center gap-1.5 rounded-full border border-gold/30 bg-gold-tint/40 px-2 py-0.5 font-mono text-[9px] tracking-[0.14em] text-gold-ink">
              <span aria-hidden className="h-1.5 w-1.5 rounded-full bg-emerald animate-pulse" />
              Live · v4.2
            </span>
          </div>

          <h1
            className="font-display font-semibold text-[clamp(44px,5.2vw,64px)] leading-[1.02] tracking-[-0.02em] text-charcoal"
            style={{ fontVariationSettings: "'opsz' 144" }}
          >
            Run every infrastructure programme
            <br />
            as{" "}
            <span className="relative inline-block">
              <em className="not-italic font-medium italic text-gold-deep">one</em>
              <svg
                aria-hidden
                className="absolute -bottom-2 left-0 w-full"
                height="10"
                viewBox="0 0 100 10"
                fill="none"
                preserveAspectRatio="none"
              >
                <path
                  d="M2 7 Q 25 1, 50 5 T 98 4"
                  stroke="#D4AF37"
                  strokeWidth="2.5"
                  strokeLinecap="round"
                  fill="none"
                />
              </svg>
            </span>
            .
          </h1>

          <p className="mt-5 max-w-[540px] text-[16px] leading-[1.65] text-slate">
            Portfolio, schedule, cost, risk, and field operations on a single spine. Trusted by
            programme leaders to deliver at scale — from rail corridors to solar farms.
          </p>

          <div className="mt-6 flex flex-wrap gap-2.5">
            <button
              type="button"
              className="group inline-flex h-12 items-center gap-2 rounded-xl bg-gold px-5 text-sm font-semibold text-paper shadow-[0_4px_14px_rgba(0,88,202,0.28)] transition-all duration-200 hover:bg-gold-deep hover:-translate-y-0.5 hover:shadow-[0_10px_28px_rgba(0,88,202,0.38)]"
            >
              Request demo
              <ArrowUpRight
                size={16}
                className="transition-transform duration-200 group-hover:translate-x-0.5 group-hover:-translate-y-0.5"
              />
            </button>
            <button
              type="button"
              className="inline-flex h-12 items-center gap-2 rounded-xl border border-gold/60 bg-paper/70 px-5 text-sm font-semibold text-gold-deep backdrop-blur transition-all duration-200 hover:border-gold hover:bg-paper hover:text-gold-ink"
            >
              Explore the platform
            </button>
          </div>

          <div className="mt-6 flex items-center gap-4">
            <div className="flex">
              {["MC", "JR", "AS", "+"].map((s, i) => (
                <div
                  key={i}
                  className={`flex h-9 w-9 items-center justify-center rounded-full border-2 border-paper shadow-sm ${i ? "-ml-2.5" : ""} font-display font-semibold text-[11px] text-gold-ink`}
                  style={{ background: "linear-gradient(135deg,#FDF6DD,#F5E7B5 60%,#E7D58A)" }}
                >
                  {s}
                </div>
              ))}
            </div>
            <div className="leading-tight">
              <div className="flex items-center gap-1.5 text-[12px]">
                <span className="text-gold-deep">★★★★★</span>
                <span className="font-semibold text-charcoal">4.8 on G2</span>
                <span className="text-ash">·</span>
                <span className="text-slate">200+ reviews</span>
              </div>
              <div className="mt-0.5 text-[12px] text-slate">
                <strong className="text-charcoal">400+ teams</strong> delivering programmes on Bipros
              </div>
            </div>
          </div>

        </div>

        {/* Sign-in card */}
        <form
          onSubmit={handleSubmit}
          className="relative z-10 rounded-2xl border border-hairline bg-paper/95 p-6 shadow-[0_12px_40px_rgba(28,28,28,0.08)] backdrop-blur lg:mt-1"
        >
          <div
            aria-hidden
            className="absolute inset-x-6 -top-px h-px"
            style={{ background: "linear-gradient(90deg, transparent, #D4AF37, transparent)" }}
          />

          <div className="flex items-baseline justify-between">
            <h3 className="font-display text-xl font-semibold tracking-tight text-charcoal">
              Welcome back
            </h3>
            <span className="flex items-center gap-1 font-mono text-[9px] uppercase tracking-[0.14em] text-ash">
              <Lock size={10} />
              Secure
            </span>
          </div>
          <div className="mt-0.5 text-xs text-slate">Sign in to your portfolio</div>

          <div className="mt-4 grid grid-cols-3 gap-1.5">
            {["Google", "Microsoft", "SSO"].map((p) => (
              <button
                key={p}
                type="button"
                onClick={ssoNotAvailable}
                className="h-10 rounded-[10px] border border-hairline bg-paper text-[11px] font-medium text-charcoal transition-all hover:border-gold hover:bg-ivory"
              >
                {p}
              </button>
            ))}
          </div>

          <div className="my-4 flex items-center gap-3 text-[10px] font-semibold uppercase tracking-[0.14em] text-ash">
            <div className="h-px flex-1 bg-hairline" />
            or with email
            <div className="h-px flex-1 bg-hairline" />
          </div>

          {error && (
            <div className="mb-3 rounded-md border border-burgundy/30 bg-burgundy/10 px-3 py-2 text-xs text-burgundy">
              {error}
            </div>
          )}

          <label className="mb-1.5 block text-xs font-semibold text-charcoal">
            Email or username
          </label>
          <input
            type="text"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            placeholder="you@company.com"
            required
            autoComplete="username"
            className="mb-3 h-10 w-full rounded-[10px] border border-divider bg-paper px-3.5 text-sm text-charcoal outline-none transition-all duration-[120ms] placeholder:text-ash hover:border-gold-deep/50 focus:border-gold focus:shadow-[0_0_0_3px_rgba(0,88,202,0.18)]"
          />

          <label className="mb-1.5 block text-xs font-semibold text-charcoal">Password</label>
          <div className="relative mb-3">
            <input
              type={showPw ? "text" : "password"}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              autoComplete="current-password"
              className="h-10 w-full rounded-[10px] border border-divider bg-paper px-3.5 pr-10 text-sm text-charcoal outline-none transition-all duration-[120ms] hover:border-gold-deep/50 focus:border-gold focus:shadow-[0_0_0_3px_rgba(0,88,202,0.18)]"
            />
            <button
              type="button"
              onClick={() => setShowPw((s) => !s)}
              aria-label={showPw ? "Hide password" : "Show password"}
              className="absolute right-2.5 top-1/2 -translate-y-1/2 rounded-md p-1 text-slate hover:text-gold-deep"
            >
              {showPw ? <EyeOff size={15} /> : <Eye size={15} />}
            </button>
          </div>

          <div className="mb-4 flex items-center justify-between text-[11px] text-slate">
            <label className="flex items-center gap-1.5">
              <input
                type="checkbox"
                checked={remember}
                onChange={(e) => setRemember(e.target.checked)}
                className="accent-[#D4AF37]"
              />
              Remember me
            </label>
            <a className="cursor-pointer font-semibold text-gold-deep hover:text-gold-ink">
              Forgot?
            </a>
          </div>

          <button
            type="submit"
            disabled={submitting}
            className="inline-flex h-12 w-full items-center justify-center rounded-xl bg-gold text-sm font-semibold text-paper shadow-[0_4px_14px_rgba(0,88,202,0.28)] transition-all duration-200 hover:-translate-y-0.5 hover:bg-gold-deep hover:shadow-[0_10px_28px_rgba(0,88,202,0.38)] disabled:translate-y-0 disabled:cursor-not-allowed disabled:opacity-70 disabled:shadow-none"
          >
            {submitting ? "Signing in…" : "Sign in"}
          </button>

          <div className="mt-4 flex justify-center gap-1.5">
            {["SOC 2", "ISO 27001", "SSO / SAML"].map((t) => (
              <span
                key={t}
                className="rounded border border-hairline bg-ivory px-2 py-1 font-mono text-[9px] uppercase tracking-[0.1em] text-slate"
              >
                {t}
              </span>
            ))}
          </div>
        </form>
      </div>

      {/* Full-width live programme telemetry — fills the visual gap below the
          sign-in card and removes the finance-flavoured 'Customer portfolios'
          framing in favour of programme/project/activity volume metrics. */}
      <div className="relative px-9 pb-14">
        <div className="overflow-hidden rounded-2xl border border-hairline bg-paper/80 backdrop-blur-sm shadow-[0_4px_20px_rgba(28,28,28,0.05)]">
          <div className="flex flex-wrap items-center justify-between gap-2 border-b border-hairline bg-ivory/60 px-5 py-3 font-mono text-[10px] uppercase tracking-[0.14em] text-slate">
            <div className="flex items-center gap-2">
              <span aria-hidden className="inline-block h-1.5 w-1.5 rounded-full bg-emerald animate-pulse" />
              <span className="text-charcoal font-semibold">Live · programme intelligence</span>
              <span className="text-ash">·</span>
              <span>Across 400+ teams</span>
            </div>
            <span>Synced 00:14 ago</span>
          </div>
          {/* gap-px on the grid + bg-hairline on the wrapper gives a uniform 1px
              divider grid that adapts cleanly across breakpoints. */}
          <div className="grid grid-cols-2 gap-px bg-hairline sm:grid-cols-3 lg:grid-cols-6">
            {liveStats.map((s) => (
              <div
                key={s.label}
                className="relative bg-paper/80 p-5"
              >
                <div className="text-[10px] font-semibold uppercase tracking-[0.12em] text-slate">
                  {s.label}
                </div>
                <div
                  className="mt-2.5 font-display text-[30px] font-semibold leading-none tracking-[-0.015em] text-charcoal"
                  style={{ fontVariationSettings: "'opsz' 144" }}
                >
                  {s.value}
                </div>
                <div
                  className={`mt-2 flex items-center gap-1 text-[11px] font-medium ${
                    s.trend === "up"
                      ? "text-emerald"
                      : s.trend === "down"
                        ? "text-burgundy"
                        : "text-slate"
                  }`}
                >
                  {s.trend === "up" && <span aria-hidden>↑</span>}
                  {s.trend === "down" && <span aria-hidden>↓</span>}
                  {s.delta}
                </div>
                {/* decorative sparkline */}
                <svg
                  aria-hidden
                  className="absolute bottom-3 right-3 opacity-60"
                  width="56"
                  height="20"
                  viewBox="0 0 56 20"
                  fill="none"
                >
                  <path
                    d="M1 15 L9 12 L17 14 L25 7 L33 9 L41 4 L48 6 L55 3"
                    stroke={
                      s.trend === "up" ? "#2E7D5B" : s.trend === "down" ? "#9B2C2C" : "#B8962E"
                    }
                    strokeWidth="1.5"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    fill="none"
                  />
                </svg>
              </div>
            ))}
          </div>
        </div>

        {/* Trusted-by wordmarks */}
        <div className="mt-7 flex flex-wrap items-center gap-x-6 gap-y-3">
          <div className="font-mono text-[10px] uppercase tracking-[0.2em] text-ash">
            Trusted by
          </div>
          {trustedBy.map((c) => (
            <div
              key={c}
              className="font-display text-[14px] font-semibold tracking-[0.04em] text-slate/75 transition-colors hover:text-charcoal"
            >
              {c}
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
