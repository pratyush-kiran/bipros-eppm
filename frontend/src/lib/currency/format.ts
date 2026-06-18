/**
 * Canonical money formatter for Bipros — the single source of truth for how a
 * monetary amount is rendered. Replaces the historical scatter of formatters
 * (formatCurrency, formatMoney, formatBudget, makeFormatter, formatCrore,
 * formatINR, …) that each made their own assumptions and drifted apart.
 *
 * Two rules, both derived from the currency CODE alone so this stays correct
 * even when the currency master (`/v1/admin/currencies`) is unavailable
 * (it is permission-gated; many money-viewing roles get a 403):
 *
 *   1. Symbol  — master glyph if we have it, else Intl's narrowSymbol, else the
 *      bare ISO code. INR → ₹, USD → $, OMR → "OMR".
 *   2. Numbering — INR uses the Indian system (en-IN grouping, Lakh/Crore);
 *      every other currency uses the international system (en-US, Thousand/
 *      Million/Billion). The compact divisor/suffix is ALWAYS chosen from the
 *      currency, so 30,000,000 reads "3 Cr" (INR) or "30 M" (OMR/USD).
 *
 * No exchange-rate conversion ever happens here — amounts are raw numbers in
 * the project's own currency; we only relabel and abbreviate.
 */

import type { CurrencyResponse } from "@/lib/api/settingsApi";

export interface CurrencyMeta {
  code: string;
  symbol?: string;
  decimalPlaces?: number;
}

export interface ResolvedCurrencyMeta {
  code: string;
  symbol: string;
  decimalPlaces: number;
  /** true when the symbol is a real glyph (₹/$/€) rather than the ISO code. */
  isGlyph: boolean;
  /** true for INR — drives Indian (Lakh/Crore, en-IN) vs international numbering. */
  isIndian: boolean;
}

export interface MoneyOpts {
  /** Abbreviate large numbers (K/M/B or k/L/Cr). Default false (full precision). */
  compact?: boolean;
  /** Override decimal places. Defaults to the currency's decimalPlaces (full) / tier default (compact). */
  decimals?: number;
  /** Render the symbol/code. Default true. */
  symbol?: boolean;
}

const DASH = "—";
const RUPEE = "₹";

/** ISO codes whose conventional symbol is a real glyph we can rely on. */
const KNOWN_GLYPHS: Record<string, string> = {
  INR: RUPEE,
  USD: "$",
  EUR: "€",
  GBP: "£",
  JPY: "¥",
};

/**
 * Resolve a currency code to a display symbol + decimal places. The master list
 * (when present) wins for custom glyphs; otherwise we fall back to Intl and then
 * to the bare code. Never throws, never depends on the master being loaded.
 */
export function resolveCurrencyMeta(
  code: string | null | undefined,
  master?: CurrencyResponse[] | null,
): ResolvedCurrencyMeta {
  const upper = (code ?? "INR").trim().toUpperCase() || "INR";
  const isIndian = upper === "INR";

  const fromMaster = master?.find((c) => c.code?.toUpperCase() === upper);

  // Symbol: master glyph → known glyph → Intl narrowSymbol → bare code.
  let symbol = fromMaster?.symbol?.trim() || KNOWN_GLYPHS[upper] || "";
  if (!symbol) {
    symbol = intlSymbol(upper) ?? upper;
  }

  // Decimal places: master → Intl → 2.
  let decimalPlaces: number | null | undefined = fromMaster?.decimalPlaces;
  if (decimalPlaces == null) decimalPlaces = intlDecimals(upper);
  if (decimalPlaces == null) decimalPlaces = 2;

  // A glyph is "real" when it differs from the ISO code (₹ ≠ INR, but OMR === OMR).
  const isGlyph = symbol.toUpperCase() !== upper;

  return { code: upper, symbol, decimalPlaces, isGlyph, isIndian };
}

function intlSymbol(code: string): string | null {
  try {
    const parts = new Intl.NumberFormat("en", {
      style: "currency",
      currency: code,
      currencyDisplay: "narrowSymbol",
    }).formatToParts(0);
    const sym = parts.find((p) => p.type === "currency")?.value;
    return sym && sym.toUpperCase() !== code ? sym : null;
  } catch {
    return null;
  }
}

function intlDecimals(code: string): number | null {
  try {
    return (
      new Intl.NumberFormat("en", { style: "currency", currency: code })
        .resolvedOptions().maximumFractionDigits ?? null
    );
  } catch {
    return null;
  }
}

interface CompactTier {
  divisor: number;
  suffix: string;
  decimals: number;
}

/** Pick the abbreviation tier from the magnitude, per numbering system. */
function compactTier(abs: number, isIndian: boolean): CompactTier {
  if (isIndian) {
    if (abs >= 1e7) return { divisor: 1e7, suffix: "Cr", decimals: 2 };
    if (abs >= 1e5) return { divisor: 1e5, suffix: "L", decimals: 2 };
    if (abs >= 1e3) return { divisor: 1e3, suffix: "k", decimals: 1 };
  } else {
    if (abs >= 1e9) return { divisor: 1e9, suffix: "B", decimals: 2 };
    if (abs >= 1e6) return { divisor: 1e6, suffix: "M", decimals: 2 };
    if (abs >= 1e3) return { divisor: 1e3, suffix: "K", decimals: 1 };
  }
  return { divisor: 1, suffix: "", decimals: 0 };
}

/**
 * Format an amount for display. `meta` is either a resolved meta (from the
 * provider) or a bare `{ code }` — both work, the latter resolves via Intl.
 */
export function formatMoney(
  amount: number | null | undefined,
  meta: CurrencyMeta | ResolvedCurrencyMeta,
  opts: MoneyOpts = {},
): string {
  if (amount == null || !Number.isFinite(amount)) return DASH;

  const resolved: ResolvedCurrencyMeta =
    "isGlyph" in meta ? meta : resolveCurrencyMeta(meta.code, undefined);
  // Allow a caller-supplied symbol/decimals on a bare meta to take effect.
  const symbol = meta.symbol?.trim() || resolved.symbol;
  const baseDecimals = meta.decimalPlaces ?? resolved.decimalPlaces;
  const { code, isIndian, isGlyph } = resolved;

  const locale = isIndian ? "en-IN" : "en-US";
  const showSymbol = opts.symbol !== false;

  let scaled = amount;
  let suffix = "";
  let decimals: number;

  if (opts.compact) {
    const tier = compactTier(Math.abs(amount), isIndian);
    scaled = amount / tier.divisor;
    suffix = tier.suffix;
    decimals = opts.decimals ?? tier.decimals;
  } else {
    decimals = opts.decimals ?? baseDecimals;
  }

  const numStr = scaled.toLocaleString(locale, {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });

  const tail = suffix ? ` ${suffix}` : "";

  if (!showSymbol) return `${numStr}${tail}`;

  // Glyph currencies lead with the symbol; code-only currencies trail the code.
  return isGlyph
    ? `${symbol}${numStr}${tail}`
    : `${numStr}${tail} ${code}`;
}
