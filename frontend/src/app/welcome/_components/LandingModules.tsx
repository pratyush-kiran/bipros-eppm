import { modules } from "./landing-data";

export function LandingModules() {
  return (
    <section className="relative px-9 py-20" style={{ background: "#141414", color: "#F5E7B5" }}>
      <div
        aria-hidden
        className="absolute inset-x-0 top-0 h-px"
        style={{ background: "linear-gradient(90deg, transparent, #D4AF37, transparent)" }}
      />
      <div className="mx-auto mb-11 max-w-[640px] text-center">
        <div className="mb-2.5 text-[10px] font-semibold uppercase tracking-[0.18em] text-gold">
          Nine modules · one model
        </div>
        <h2
          className="font-display text-[38px] font-semibold leading-[1.1] tracking-tight text-paper"
          style={{ fontVariationSettings: "'opsz' 144" }}
        >
          Built for the{" "}
          <em className="not-italic font-medium italic text-gold">whole</em> programme
        </h2>
        <p className="mt-2.5 text-sm text-[rgba(245,231,181,0.65)]">
          Every module shares a single data model, so cost reconciles to schedule, risk rolls up to
          portfolio, and field progress flows back to EVM without export-and-reimport.
        </p>
      </div>
      <div className="mx-auto grid max-w-[1040px] gap-3 md:grid-cols-3">
        {modules.map((m) => (
          <div
            key={m.n}
            className="group cursor-pointer rounded-[10px] border p-5 transition-all duration-200 hover:-translate-y-0.5"
            style={{ background: "rgba(255,255,255,0.02)", borderColor: "rgba(0,88,202,0.18)" }}
          >
            <div className="font-mono text-[10px] tracking-[0.14em] text-gold">{m.n}</div>
            <div
              className="mt-2.5 flex h-7 w-7 items-center justify-center rounded-[7px] text-gold"
              style={{ background: "rgba(0,88,202,0.12)" }}
            >
              <m.Icon size={14} strokeWidth={1.5} />
            </div>
            <h5 className="mt-3 font-display text-lg font-semibold text-paper">{m.title}</h5>
            <p className="mt-1 text-xs leading-relaxed" style={{ color: "rgba(245,231,181,0.55)" }}>
              {m.blurb}
            </p>
          </div>
        ))}
      </div>
    </section>
  );
}
