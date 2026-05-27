function Art({ children }: { children: React.ReactNode }) {
  return (
    <div
      className="relative flex h-[110px] items-center justify-center overflow-hidden text-gold"
      style={{ background: "linear-gradient(180deg,#141414,#0B0B0B)" }}
    >
      <div
        aria-hidden
        className="absolute inset-0"
        style={{ background: "radial-gradient(circle at 70% 30%, rgba(0,88,202,0.15), transparent 60%)" }}
      />
      <svg width="90" height="60" viewBox="0 0 90 60" fill="none" stroke="currentColor" strokeWidth="1" className="relative z-10">
        {children}
      </svg>
    </div>
  );
}

const industries = [
  {
    n: "01", name: "Road & Rail",
    blurb: "Linear mega-projects with corridor-based phasing.",
    art: (
      <>
        <path d="M5 50 L35 25 L55 40 L85 15" />
        <circle cx="5" cy="50" r="2" fill="currentColor" />
        <circle cx="35" cy="25" r="2" fill="currentColor" />
        <circle cx="55" cy="40" r="2" fill="currentColor" />
        <circle cx="85" cy="15" r="2" fill="currentColor" />
        <path d="M0 55 L90 55" strokeDasharray="2 3" />
      </>
    ),
  },
  {
    n: "02", name: "Energy",
    blurb: "Solar, wind, and transmission programmes.",
    art: (
      <>
        <path d="M45 10 L60 30 L75 10" />
        <path d="M15 10 L30 30 L45 10" />
        <path d="M10 50 L80 50" />
        <path d="M20 50 L20 30 M40 50 L40 30 M60 50 L60 30" />
      </>
    ),
  },
  {
    n: "03", name: "Water",
    blurb: "Dams, treatment, distribution networks.",
    art: (
      <>
        <path d="M10 45 Q25 30 40 45 T70 45 T90 45" />
        <path d="M10 50 Q25 35 40 50 T70 50 T90 50" opacity=".5" />
        <circle cx="25" cy="20" r="4" />
        <path d="M25 24 L25 35" />
      </>
    ),
  },
  {
    n: "04", name: "Urban Rail",
    blurb: "Metro, light rail, and station programmes.",
    art: (
      <>
        <path d="M5 40 L85 40" />
        <path d="M5 30 L85 30" strokeDasharray="3 2" />
        <rect x="20" y="15" width="10" height="25" />
        <rect x="55" y="15" width="10" height="25" />
      </>
    ),
  },
];

export function LandingIndustries() {
  return (
    <section className="bg-ivory px-9 py-20">
      <div className="mx-auto mb-10 max-w-[640px] text-center">
        <div className="mb-2.5 text-[10px] font-semibold uppercase tracking-[0.18em] text-gold-deep">
          Built for heavy infrastructure
        </div>
        <h2
          className="font-display text-[36px] font-semibold leading-[1.1] tracking-tight text-charcoal"
          style={{ fontVariationSettings: "'opsz' 144" }}
        >
          Industries we serve
        </h2>
      </div>
      <div className="mx-auto grid max-w-[1180px] gap-4 md:grid-cols-2 lg:grid-cols-4">
        {industries.map((i) => (
          <div
            key={i.n}
            className="cursor-pointer overflow-hidden rounded-xl border border-hairline bg-paper transition-all duration-200 hover:shadow-[0_4px_20px_rgba(28,28,28,0.05)] hover:-translate-y-0.5"
          >
            <Art>{i.art}</Art>
            <div className="p-5">
              <div className="font-mono text-[10px] tracking-[0.14em] text-gold-deep">{i.n}</div>
              <h5 className="mt-1 font-display text-lg font-semibold tracking-tight text-charcoal">
                {i.name}
              </h5>
              <p className="mt-1 text-xs leading-relaxed text-slate">{i.blurb}</p>
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}
