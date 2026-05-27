"use client";

export type ResolvedTokens = {
  gold: string;
  goldDeep: string;
  goldInk: string;
  goldTint: string;
  emerald: string;
  bronzeWarn: string;
  amberFlame: string;
  burgundy: string;
  steel: string;
  charcoal: string;
  slate: string;
  hairline: string;
  surface: string;
  gridColor: string;
  textPrimary: string;
  textSecondary: string;
};

// Material-3 fallback (SSR / no-document only — readTokens() overrides with the
// live CSS variables on the client, which carry the active theme).
const FALLBACK: ResolvedTokens = {
  gold: "#B0C6FF",
  goldDeep: "#CDD9FF",
  goldInk: "#8AB4FF",
  goldTint: "#1B2740",
  emerald: "#4EDEA3",
  bronzeWarn: "#FFB690",
  amberFlame: "#FF8A50",
  burgundy: "#FFB4AB",
  steel: "#94A3B8",
  charcoal: "#E1E2ED",
  slate: "#C2C6D7",
  hairline: "#424654",
  surface: "#1D1F27",
  gridColor: "rgba(225, 226, 237, 0.05)",
  textPrimary: "#E1E2ED",
  textSecondary: "#C2C6D7",
};

function readVar(root: HTMLElement, name: string, fallback: string): string {
  const v = getComputedStyle(root).getPropertyValue(name).trim();
  return v.length > 0 ? v : fallback;
}

export function readTokens(): ResolvedTokens {
  if (typeof document === "undefined") return FALLBACK;
  const root = document.documentElement;
  return {
    gold: readVar(root, "--gold", FALLBACK.gold),
    goldDeep: readVar(root, "--gold-deep", FALLBACK.goldDeep),
    goldInk: readVar(root, "--gold-ink", FALLBACK.goldInk),
    goldTint: readVar(root, "--gold-tint", FALLBACK.goldTint),
    emerald: readVar(root, "--emerald", FALLBACK.emerald),
    bronzeWarn: readVar(root, "--bronze-warn", FALLBACK.bronzeWarn),
    amberFlame: readVar(root, "--amber-flame", FALLBACK.amberFlame),
    burgundy: readVar(root, "--burgundy", FALLBACK.burgundy),
    steel: readVar(root, "--steel", FALLBACK.steel),
    charcoal: readVar(root, "--charcoal", FALLBACK.charcoal),
    slate: readVar(root, "--slate", FALLBACK.slate),
    hairline: readVar(root, "--hairline", FALLBACK.hairline),
    surface: readVar(root, "--surface", FALLBACK.surface),
    gridColor: readVar(root, "--grid-color", FALLBACK.gridColor),
    textPrimary: readVar(root, "--text-primary", FALLBACK.textPrimary),
    textSecondary: readVar(root, "--text-secondary", FALLBACK.textSecondary),
  };
}

export function paletteFor(type: string, t: ResolvedTokens): string[] {
  switch (type) {
    case "line":
      return [t.steel, t.gold, t.emerald, t.burgundy, t.bronzeWarn, t.amberFlame];
    case "scatter":
      return [t.burgundy, t.gold, t.emerald, t.steel];
    case "pie":
    case "donut":
      return [t.gold, t.emerald, t.steel, t.bronzeWarn, t.burgundy, t.amberFlame];
    case "bar":
    case "stacked-bar":
    case "waterfall":
    default:
      return [t.gold, t.emerald, t.steel, t.bronzeWarn, t.burgundy, t.goldDeep];
  }
}

type AnyObj = Record<string, unknown>;

function isObj(v: unknown): v is AnyObj {
  return v !== null && typeof v === "object" && !Array.isArray(v);
}

function asArray<T>(v: T | T[] | undefined): T[] {
  if (v === undefined) return [];
  return Array.isArray(v) ? v : [v];
}

function deepClone<T>(v: T): T {
  if (typeof structuredClone === "function") return structuredClone(v);
  return JSON.parse(JSON.stringify(v)) as T;
}

function mergeAxis(axis: AnyObj, t: ResolvedTokens): AnyObj {
  const axisLine = isObj(axis.axisLine) ? { ...axis.axisLine } : {};
  axisLine.lineStyle = { ...(isObj(axisLine.lineStyle) ? axisLine.lineStyle : {}), color: t.hairline };

  const splitLine = isObj(axis.splitLine) ? { ...axis.splitLine } : {};
  splitLine.lineStyle = { ...(isObj(splitLine.lineStyle) ? splitLine.lineStyle : {}), color: t.gridColor };

  const axisLabel = isObj(axis.axisLabel) ? { ...axis.axisLabel } : {};
  axisLabel.color = t.textSecondary;

  const axisTick = isObj(axis.axisTick) ? { ...axis.axisTick } : {};
  axisTick.lineStyle = { ...(isObj(axisTick.lineStyle) ? axisTick.lineStyle : {}), color: t.hairline };

  return { ...axis, axisLine, splitLine, axisLabel, axisTick };
}

function mergeLegend(legend: AnyObj, t: ResolvedTokens): AnyObj {
  const textStyle = isObj(legend.textStyle) ? { ...legend.textStyle } : {};
  textStyle.color = t.textSecondary;
  return { ...legend, textStyle };
}

function applyGauge(series: AnyObj[], t: ResolvedTokens): AnyObj[] {
  return series.map((s) => {
    if (!isObj(s) || s.type !== "gauge") return s;
    const next: AnyObj = { ...s };
    next.axisLine = {
      ...(isObj(s.axisLine) ? s.axisLine : {}),
      lineStyle: {
        ...(isObj(s.axisLine) && isObj((s.axisLine as AnyObj).lineStyle)
          ? ((s.axisLine as AnyObj).lineStyle as AnyObj)
          : {}),
        color: [
          [0.5, t.burgundy],
          [0.83, t.bronzeWarn],
          [1.0, t.emerald],
        ],
      },
    };
    next.pointer = {
      ...(isObj(s.pointer) ? s.pointer : {}),
      itemStyle: { color: t.charcoal },
    };
    next.progress = {
      ...(isObj(s.progress) ? s.progress : {}),
      show: true,
      itemStyle: { color: t.gold },
    };
    next.axisTick = {
      ...(isObj(s.axisTick) ? s.axisTick : {}),
      lineStyle: { color: t.hairline },
    };
    next.splitLine = {
      ...(isObj(s.splitLine) ? s.splitLine : {}),
      lineStyle: { color: t.hairline },
    };
    next.title = { ...(isObj(s.title) ? s.title : {}), color: t.textSecondary };
    next.detail = { ...(isObj(s.detail) ? s.detail : {}), color: t.charcoal };
    return next;
  });
}

function lastNumberInData(data: unknown): number | null {
  if (!Array.isArray(data) || data.length === 0) return null;
  const last = data[data.length - 1];
  if (typeof last === "number") return last;
  if (isObj(last) && typeof last.value === "number") return last.value;
  if (Array.isArray(last)) {
    const n = last[last.length - 1];
    if (typeof n === "number") return n;
  }
  return null;
}

function applyWaterfall(series: AnyObj[], t: ResolvedTokens): AnyObj[] {
  // Backend (CostInsightsCollector) emits at least one visible bar series for Budget/Actual/Variance.
  // Find the visible (non-placeholder) bar series and color them in order.
  const palette = [t.gold, t.steel];
  const visibleIdx: number[] = [];
  series.forEach((s, i) => {
    if (!isObj(s)) return;
    const name = typeof s.name === "string" ? s.name.toLowerCase() : "";
    // Backend uses an empty/placeholder stack series for the waterfall offset; skip those.
    if (name.includes("placeholder") || name === "") return;
    if (s.stack !== undefined && palette.length === 0) return;
    visibleIdx.push(i);
  });

  return series.map((s, i) => {
    if (!isObj(s)) return s;
    const slot = visibleIdx.indexOf(i);
    if (slot < 0) return s;
    const name = typeof s.name === "string" ? s.name.toLowerCase() : "";
    let color: string;
    if (name.includes("variance")) {
      const v = lastNumberInData(s.data);
      color = v !== null && v >= 0 ? t.emerald : t.burgundy;
    } else if (slot === 0) {
      color = t.gold;
    } else if (slot === 1) {
      color = t.steel;
    } else {
      color = t.emerald;
    }
    return {
      ...s,
      itemStyle: { ...(isObj(s.itemStyle) ? s.itemStyle : {}), color },
    };
  });
}

function applyStackedBar(series: AnyObj[], t: ResolvedTokens): AnyObj[] {
  const palette = paletteFor("bar", t);
  return series.map((s, i) => {
    if (!isObj(s)) return s;
    const color = palette[i % palette.length];
    return {
      ...s,
      itemStyle: { ...(isObj(s.itemStyle) ? s.itemStyle : {}), color },
    };
  });
}

function applyTreemap(series: AnyObj[], t: ResolvedTokens): AnyObj[] {
  return series.map((s) => {
    if (!isObj(s) || s.type !== "treemap") return s;
    return {
      ...s,
      levels: [
        { itemStyle: { borderColor: t.hairline, borderWidth: 1, gapWidth: 2, color: t.gold } },
        { itemStyle: { color: t.goldDeep, gapWidth: 1 } },
        { itemStyle: { color: t.goldInk } },
      ],
      label: {
        ...(isObj(s.label) ? s.label : {}),
        color: t.charcoal,
      },
    };
  });
}

function applyArea(series: AnyObj[], t: ResolvedTokens): AnyObj[] {
  return series.map((s) => {
    if (!isObj(s)) return s;
    const isLine = s.type === "line" || s.type === undefined;
    if (!isLine) return s;
    return {
      ...s,
      lineStyle: { ...(isObj(s.lineStyle) ? s.lineStyle : {}), color: t.gold },
      itemStyle: { ...(isObj(s.itemStyle) ? s.itemStyle : {}), color: t.gold },
      areaStyle: {
        color: {
          type: "linear",
          x: 0,
          y: 0,
          x2: 0,
          y2: 1,
          colorStops: [
            { offset: 0, color: t.goldTint },
            { offset: 1, color: "transparent" },
          ],
        },
      },
      smooth: true,
    };
  });
}

export function applyThemeToOption(
  type: string,
  option: AnyObj,
  t: ResolvedTokens,
): AnyObj {
  const opt = deepClone(option);

  // Top-level cycling palette
  opt.color = paletteFor(type, t);

  // Global text style
  opt.textStyle = {
    ...(isObj(opt.textStyle) ? opt.textStyle : {}),
    color: t.textSecondary,
  };

  // Tooltip surface
  opt.tooltip = {
    ...(isObj(opt.tooltip) ? opt.tooltip : {}),
    backgroundColor: t.surface,
    borderColor: t.hairline,
    textStyle: {
      ...(isObj(opt.tooltip) && isObj((opt.tooltip as AnyObj).textStyle)
        ? ((opt.tooltip as AnyObj).textStyle as AnyObj)
        : {}),
      color: t.textPrimary,
    },
  };

  // Axes
  if (opt.xAxis !== undefined) {
    const arr = asArray(opt.xAxis as AnyObj | AnyObj[]);
    const themed = arr.map((a) => mergeAxis(a, t));
    opt.xAxis = Array.isArray(opt.xAxis) ? themed : themed[0];
  }
  if (opt.yAxis !== undefined) {
    const arr = asArray(opt.yAxis as AnyObj | AnyObj[]);
    const themed = arr.map((a) => mergeAxis(a, t));
    opt.yAxis = Array.isArray(opt.yAxis) ? themed : themed[0];
  }

  // Legend
  if (opt.legend !== undefined) {
    const arr = asArray(opt.legend as AnyObj | AnyObj[]);
    const themed = arr.map((l) => mergeLegend(l, t));
    opt.legend = Array.isArray(opt.legend) ? themed : themed[0];
  }

  // Series-level overrides per chart type
  const series = asArray(opt.series as AnyObj | AnyObj[]).map((s) => (isObj(s) ? s : s));

  let nextSeries: AnyObj[] = series;
  switch (type) {
    case "gauge":
    case "dual-gauge":
      nextSeries = applyGauge(series, t);
      break;
    case "waterfall":
      nextSeries = applyWaterfall(series, t);
      break;
    case "stacked-bar":
      nextSeries = applyStackedBar(series, t);
      break;
    case "treemap":
      nextSeries = applyTreemap(series, t);
      break;
    case "area":
      nextSeries = applyArea(series, t);
      break;
    default:
      // bar / line / pie / donut / scatter rely on top-level color cycling
      break;
  }

  if (opt.series !== undefined) {
    opt.series = Array.isArray(opt.series) ? nextSeries : nextSeries[0];
  }

  return opt;
}
