import { useQuery } from "@tanstack/react-query";
import { settingsApi, type CurrencyResponse } from "@/lib/api/settingsApi";

const DEFAULT_CURRENCY: CurrencyResponse = {
  id: "",
  code: "INR",
  name: "Indian Rupee",
  symbol: "\u20B9",
  exchangeRate: 1,
  isBaseCurrency: true,
  decimalPlaces: 2,
};

export function useCurrency() {
  const { data } = useQuery({
    queryKey: ["currencies"],
    queryFn: () => settingsApi.listCurrencies(),
    staleTime: 5 * 60 * 1000,
  });

  const currencies = data?.data ?? [];
  const baseCurrency = currencies.find((c) => c.isBaseCurrency) ?? DEFAULT_CURRENCY;

  const formatCurrency = (amount: number | null | undefined): string => {
    const val = amount ?? 0;
    return `${baseCurrency.symbol}${val.toLocaleString(undefined, {
      minimumFractionDigits: baseCurrency.decimalPlaces,
      maximumFractionDigits: baseCurrency.decimalPlaces,
    })}`;
  };

  return { baseCurrency, currencies, formatCurrency };
}

/**
 * Standalone currency formatter using the default symbol.
 * Use inside components that don't need the full hook (e.g., chart formatters).
 */
export function formatDefaultCurrency(amount: number | null | undefined, symbol = "\u20B9"): string {
  const val = amount ?? 0;
  return `${symbol}${val.toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
}

/**
 * Currency-aware money formatter. Uses the currency CODE as a suffix so it works for
 * any currency without requiring a glyph lookup (e.g. "1,23,456 INR" or "5,000 OMR").
 * For INR uses en-IN locale (lakh/crore grouping); all others use en-US.
 *
 * @param amount   Raw monetary amount in the currency's base unit.
 * @param currency ISO 4217 code, e.g. "INR", "OMR", "USD". Defaults to "INR".
 * @param fractionDigits Decimal places (default 0 for clean whole-number display).
 */
export function formatMoney(
  amount: number | null | undefined,
  currency: string | null | undefined,
  fractionDigits = 0,
): string {
  if (amount == null || !Number.isFinite(amount)) return "\u2014";
  const code = (currency ?? "INR").toUpperCase();
  const locale = code === "INR" ? "en-IN" : "en-US";
  const formatted = amount.toLocaleString(locale, {
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  });
  return `${formatted} ${code}`;
}
