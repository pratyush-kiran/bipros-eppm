"use client";

/**
 * The left-column editorial overlay on the cinematic backdrop: eyebrow,
 * two-line display headline, supporting paragraph, three programme stats,
 * and (on lg+) a pulled quote from a programme director.
 */
export function CinematicEditorial() {
  return (
    <div className="relative z-10 flex h-full flex-col justify-end px-6 pb-10 pt-16 lg:px-14 lg:pb-16 lg:pt-24">
      <div className="cn-rise cn-d1 mb-5 inline-flex items-center gap-2 text-[10.5px] uppercase tracking-[0.24em] text-[#F4B36A]">
        <span aria-hidden className="inline-block h-px w-7 bg-[#F4B36A]" />
        Site of record · twilight, on schedule
      </div>
      <h1
        className="cn-rise cn-d2 max-w-2xl text-[44px] font-medium leading-[1.04] tracking-[-0.025em] text-white sm:text-[56px] lg:text-[64px]"
        style={{ fontVariationSettings: "'opsz' 144" }}
      >
        Behind every skyline,
        <span className="block italic font-normal text-[#F4B36A]">a single source of truth.</span>
      </h1>
      <p className="cn-rise cn-d3 mt-6 max-w-xl text-[14.5px] leading-[1.7] text-white/70">
        Bipros holds schedule, cost, contract and risk for the world&apos;s most
        demanding capital programmes — so the people closing the day shift,
        and the people closing the books, see the same numbers.
      </p>

      <div className="cn-rise cn-d4 mt-8 flex max-w-xl flex-wrap items-end gap-x-10 gap-y-4 border-t border-white/[0.10] pt-6">
        {STATS.map((s) => (
          <div key={s.label}>
            <div
              className="text-[26px] font-medium leading-none text-white tabular-nums sm:text-[30px]"
              style={{ fontVariationSettings: "'opsz' 144" }}
            >
              {s.value}
            </div>
            <div className="mt-1.5 text-[10.5px] uppercase tracking-[0.18em] text-white/55">
              {s.label}
            </div>
          </div>
        ))}
      </div>

      <figure className="cn-rise cn-d5 mt-8 hidden max-w-lg border-l-2 border-[#F4B36A]/50 pl-5 lg:block">
        <blockquote className="text-[15.5px] leading-[1.55] text-white/85">
          &ldquo;The crane comes down at 19:00. The numbers settle by 19:02.
          Bipros is the only system that keeps up with the site.&rdquo;
        </blockquote>
        <figcaption className="mt-3 flex items-center gap-2 font-mono text-[10px] uppercase tracking-[0.18em] text-white/55">
          <span aria-hidden className="inline-block h-px w-5 bg-[#F4B36A]" />
          Programme Director · Tower One · Riyadh
        </figcaption>
      </figure>
    </div>
  );
}

const STATS: Array<{ value: string; label: string }> = [
  { value: "€18B+", label: "Capital under control" },
  { value: "184K", label: "Activities tracked" },
  { value: "47", label: "Active sites" },
];
