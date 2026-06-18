"use client";

/**
 * Single source of truth for the CURRENT project's currency on the client.
 *
 * Mounted once per project (in projects/[projectId]/layout.tsx, which already
 * holds the resolved project), it lets any descendant — however deep — format
 * money in the project's currency via `useProjectCurrency()` without each
 * component refetching the project or guessing INR.
 *
 * The currency master (`/v1/admin/currencies`) is only an ENRICHMENT: it is
 * permission-gated and 403s for many money-viewing roles, so the symbol and
 * numbering are derived from the ISO code alone (see resolveCurrencyMeta). A
 * failed/empty master never changes the displayed symbol.
 */

import { createContext, useContext, useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { settingsApi } from "@/lib/api/settingsApi";
import {
  resolveCurrencyMeta,
  formatMoney,
  type MoneyOpts,
  type ResolvedCurrencyMeta,
} from "./format";

export interface ProjectCurrency extends ResolvedCurrencyMeta {
  /** Full-precision money (tables, line items). */
  money: (amount: number | null | undefined, opts?: MoneyOpts) => string;
  /** Abbreviated money (dashboards, cards): K/M/B or k/L/Cr per currency. */
  moneyCompact: (amount: number | null | undefined, opts?: MoneyOpts) => string;
}

const ProjectCurrencyContext = createContext<ProjectCurrency | null>(null);

export function ProjectCurrencyProvider({
  currency,
  children,
}: {
  /** The project's ISO budgetCurrency (e.g. "INR", "OMR"). */
  currency: string | null | undefined;
  children: React.ReactNode;
}) {
  // Enrichment only — failure is swallowed; we still resolve via ISO code.
  const { data } = useQuery({
    queryKey: ["currencies"],
    queryFn: () => settingsApi.listCurrencies(),
    staleTime: 5 * 60 * 1000,
    retry: false,
  });
  const master = data?.data ?? null;

  const value = useMemo<ProjectCurrency>(() => {
    const meta = resolveCurrencyMeta(currency, master);
    return {
      ...meta,
      money: (amount, opts) => formatMoney(amount, meta, opts),
      moneyCompact: (amount, opts) => formatMoney(amount, meta, { compact: true, ...opts }),
    };
  }, [currency, master]);

  return (
    <ProjectCurrencyContext.Provider value={value}>
      {children}
    </ProjectCurrencyContext.Provider>
  );
}

/** Throws if used outside a project route — use the Optional variant on shared/portfolio surfaces. */
export function useProjectCurrency(): ProjectCurrency {
  const ctx = useContext(ProjectCurrencyContext);
  if (!ctx) {
    throw new Error(
      "useProjectCurrency must be used within a ProjectCurrencyProvider (project route only)",
    );
  }
  return ctx;
}

/** Returns null outside a project route (for components reused on portfolio pages). */
export function useProjectCurrencyOptional(): ProjectCurrency | null {
  return useContext(ProjectCurrencyContext);
}
