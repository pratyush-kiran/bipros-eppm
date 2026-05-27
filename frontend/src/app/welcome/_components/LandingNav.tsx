"use client";

import { withBasePath } from "@/lib/basePath";

export function LandingNav() {
  return (
    <nav className="relative flex items-center bg-paper border-b border-hairline px-9 py-4">
      <div
        aria-hidden
        className="absolute inset-x-0 -bottom-px h-px"
        style={{ background: "linear-gradient(90deg, transparent, #D4AF37 20%, #D4AF37 80%, transparent)", opacity: 0.4 }}
      />
      <div className="flex items-center gap-2.5">
        <img
          src={withBasePath("/bipros-logo.png")}
          alt="Bipros"
          width={28}
          height={28}
          className="h-7 w-7 rounded-[7px] object-contain"
        />
        <span className="font-display font-semibold text-xl text-charcoal tracking-tight">Bipros</span>
      </div>
      <div className="mx-auto hidden md:flex items-center gap-7 text-[13px] text-slate">
        <a className="hover:text-charcoal cursor-pointer">Platform</a>
        <a className="hover:text-charcoal cursor-pointer">Industries</a>
        <a className="hover:text-charcoal cursor-pointer">Customers</a>
        <a className="hover:text-charcoal cursor-pointer">Pricing</a>
        <a className="hover:text-charcoal cursor-pointer">Resources</a>
      </div>
      <div className="flex items-center gap-2.5 ml-auto md:ml-0">
        <button type="button" className="h-9 px-3 text-sm font-semibold text-charcoal rounded-lg hover:bg-ivory">
          Sign in
        </button>
        <button
          type="button"
          className="inline-flex h-9 items-center gap-1.5 rounded-lg bg-gold px-4 text-sm font-semibold text-paper transition-all duration-200 hover:bg-gold-deep hover:shadow-[0_4px_14px_rgba(0,88,202,0.3)] hover:-translate-y-px"
        >
          Request demo
        </button>
      </div>
    </nav>
  );
}
