import type { CSSProperties } from "react";
import type { LucideIcon } from "lucide-react";
import { Cloud, CloudRain, CloudSun, Sun, Thermometer, Wind } from "lucide-react";

export type WeatherBucket = "sunny" | "cloudy" | "rainy" | "hot" | "cold" | "neutral";

export interface WeatherTheme {
  bucket: WeatherBucket;
  Icon: LucideIcon;
  /** Inline style applied to the sticky day header. Uses background-image so the
   *  underlying Tailwind background-color (`bg-ivory/85`) remains the base layer. */
  headerStyle: CSSProperties;
  /** Tailwind classes for the weather pill (bg + border + text). */
  pillClass: string;
  /** Tailwind class for the icon inside the pill. */
  iconClass: string;
}

const NEUTRAL: WeatherTheme = {
  bucket: "neutral",
  Icon: CloudSun,
  headerStyle: {},
  pillClass: "text-slate",
  iconClass: "text-slate",
};

const tint = (token: string, strength = 70): string =>
  `linear-gradient(to right, color-mix(in srgb, var(${token}) ${strength}%, transparent), transparent 65%)`;

const THEMES: Record<Exclude<WeatherBucket, "neutral">, WeatherTheme> = {
  sunny: {
    bucket: "sunny",
    Icon: Sun,
    headerStyle: { backgroundImage: tint("--gold-tint", 75) },
    pillClass:
      "rounded-full border border-gold/40 bg-gold-tint/70 px-2 py-0.5 text-gold-ink",
    iconClass: "text-gold-deep",
  },
  cloudy: {
    bucket: "cloudy",
    Icon: Cloud,
    headerStyle: { backgroundImage: tint("--parchment", 90) },
    pillClass:
      "rounded-full border border-hairline bg-parchment/80 px-2 py-0.5 text-slate",
    iconClass: "text-slate",
  },
  rainy: {
    bucket: "rainy",
    Icon: CloudRain,
    headerStyle: { backgroundImage: tint("--steel", 18) },
    pillClass:
      "rounded-full border border-steel/30 bg-steel/10 px-2 py-0.5 text-steel",
    iconClass: "text-steel",
  },
  hot: {
    bucket: "hot",
    Icon: Thermometer,
    headerStyle: { backgroundImage: tint("--amber-flame", 16) },
    pillClass:
      "rounded-full border border-amber-flame/40 bg-amber-flame/10 px-2 py-0.5 text-amber-flame",
    iconClass: "text-amber-flame",
  },
  cold: {
    bucket: "cold",
    Icon: Wind,
    headerStyle: { backgroundImage: tint("--steel", 10) },
    pillClass:
      "rounded-full border border-divider bg-divider/60 px-2 py-0.5 text-steel",
    iconClass: "text-steel",
  },
};

const PATTERNS: Array<{ bucket: Exclude<WeatherBucket, "neutral">; test: RegExp }> = [
  { bucket: "rainy", test: /\b(rain|rainy|drizzle|shower|storm|thunder)\b/ },
  { bucket: "hot", test: /\b(hot|humid|heat|heatwave|scorching)\b/ },
  { bucket: "cold", test: /\b(cold|chilly|wind|windy|frost|snow)\b/ },
  { bucket: "sunny", test: /\b(clear|sunny|fair|bright)\b/ },
  { bucket: "cloudy", test: /\b(cloud|cloudy|overcast|hazy|fog|foggy|mist)\b/ },
];

export function getWeatherTheme(raw: string | null | undefined): WeatherTheme {
  if (!raw) return NEUTRAL;
  const s = raw.trim().toLowerCase();
  if (!s) return NEUTRAL;
  for (const { bucket, test } of PATTERNS) {
    if (test.test(s)) return THEMES[bucket];
  }
  return NEUTRAL;
}
