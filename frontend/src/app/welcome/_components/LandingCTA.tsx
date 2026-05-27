export function LandingCTA() {
  return (
    <section
      className="relative overflow-hidden px-9 py-16 text-center"
      style={{ background: "linear-gradient(180deg,#141414 0%,#0B0B0B 100%)" }}
    >
      <div
        aria-hidden
        className="absolute inset-0"
        style={{ background: "radial-gradient(circle at 50% 50%, rgba(0,88,202,0.12), transparent 60%)" }}
      />
      <div className="relative mx-auto max-w-[720px]">
        <div className="mb-3 text-[10px] font-semibold uppercase tracking-[0.18em] text-gold">
          Ready when you are
        </div>
        <h2
          className="font-display text-[42px] font-semibold leading-[1.1] tracking-[-0.015em] text-paper"
          style={{ fontVariationSettings: "'opsz' 144" }}
        >
          Run every programme{" "}
          <em className="not-italic font-medium italic text-gold">as one.</em>
        </h2>
        <p className="mt-4 text-[15px]" style={{ color: "rgba(245,231,181,0.7)" }}>
          See Bipros EPPM on your own data. Demo in 30 minutes, pilot in two weeks.
        </p>
        <div className="mt-7 flex justify-center gap-2.5">
          <button
            type="button"
            className="inline-flex h-12 items-center rounded-xl bg-gold px-5 text-sm font-semibold text-paper transition-all duration-200 hover:bg-gold-deep hover:shadow-[0_4px_14px_rgba(0,88,202,0.3)] hover:-translate-y-px"
          >
            Request demo →
          </button>
          <button
            type="button"
            className="inline-flex h-12 items-center rounded-xl border border-gold bg-transparent px-5 text-sm font-semibold text-gold hover:bg-[rgba(0,88,202,0.1)]"
          >
            Download the whitepaper
          </button>
        </div>
      </div>
    </section>
  );
}
