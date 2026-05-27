export interface PaletteValues {
  background: string;
  backgroundSubtle: string;
  backgroundMuted: string;
  foreground: string;
  foregroundSecondary: string;
  foregroundMuted: string;
  accent: string;
  accentHover: string;
  accentSubtle: string;
  accentTint: string;
  border: string;
  borderSubtle: string;
  success: string;
  warning: string;
  danger: string;
  info: string;
  amberFlame: string;
  logoPrimary: string;
  logoSecondary: string;
}

export interface ThemeDefinition {
  id: string;
  name: string;
  description?: string;
  light: PaletteValues;
  dark: PaletteValues;
  borderRadius: 4 | 6 | 8 | 12;
  fontFamily?: string;
  isCustom?: boolean;
  createdAt?: string;
  logoLight?: string;
  logoDark?: string;
  appNamePrimary?: string;
  appNameSecondary?: string;
}

export const DEFAULT_THEME_ID = "classic-gold";

function makePalette(
  background: string,
  backgroundSubtle: string,
  backgroundMuted: string,
  foreground: string,
  foregroundSecondary: string,
  foregroundMuted: string,
  accent: string,
  accentHover: string,
  accentSubtle: string,
  accentTint: string,
  border: string,
  borderSubtle: string,
  success: string,
  warning: string,
  danger: string,
  info: string,
  amberFlame: string,
  logoPrimary: string,
  logoSecondary: string
): PaletteValues {
  return {
    background,
    backgroundSubtle,
    backgroundMuted,
    foreground,
    foregroundSecondary,
    foregroundMuted,
    accent,
    accentHover,
    accentSubtle,
    accentTint,
    border,
    borderSubtle,
    success,
    warning,
    danger,
    info,
    amberFlame,
    logoPrimary,
    logoSecondary,
  };
}

export const PREDEFINED_THEMES: ThemeDefinition[] = [
  {
    // NOTE: id kept as "classic-gold" so existing stored/backend selections and
    // the DEFAULT_THEME_ID still resolve — its palette is now Material-3.
    id: "classic-gold",
    name: "Material 3",
    description: "Material-3 dark-luxury identity (blue / green / orange).",
    borderRadius: 6,
    fontFamily: undefined,
    light: makePalette(
      "#FDFBFF", "#F5F3FB", "#EFF0F7",
      "#1A1C20", "#44474F", "#74777F",
      "#0058CA", "#00429B", "#001A43", "#D9E2FF",
      "#C4C6CF", "#E3E4EB",
      "#006C4E", "#8A4B2F", "#BA1A1A", "#475569",
      "#B1480E",
      "#1A1C20", "#0058CA"
    ),
    dark: makePalette(
      "#10131B", "#191B23", "#1D1F27",
      "#E1E2ED", "#C2C6D7", "#8C90A0",
      "#B0C6FF", "#CDD9FF", "#8AB4FF", "#1B2740",
      "#424654", "#32353D",
      "#4EDEA3", "#FFB690", "#FFB4AB", "#94A3B8",
      "#FF8A50",
      "#E1E2ED", "#B0C6FF"
    ),
  },
  {
    id: "ocean-blue",
    name: "Ocean Blue",
    description: "Crisp maritime palette with sky accents.",
    borderRadius: 6,
    fontFamily: undefined,
    light: makePalette(
      "#FFFFFF", "#F0F7FF", "#E2F0FF",
      "#1E3A5F", "#4A6C8C", "#8BA3B8",
      "#0EA5E9", "#0284C7", "#0C4A6E", "#BAE6FD",
      "#CBD5E1", "#E2E8F0",
      "#16A34A", "#CA8A04", "#DC2626", "#475569",
      "#F59E0B",
      "#1E3A5F", "#0284C7"
    ),
    dark: makePalette(
      "#0A1628", "#0F2240", "#132D54",
      "#E2F0FF", "#93C5FD", "#64748B",
      "#38BDF8", "#7DD3FC", "#E0F2FE", "#0C4A6E",
      "#1E293B", "#334155",
      "#4ADE80", "#FACC15", "#F87171", "#94A3B8",
      "#FBBF24",
      "#E2F0FF", "#7DD3FC"
    ),
  },
  {
    id: "emerald-forest",
    name: "Emerald Forest",
    description: "Organic greens with earth-tone warmth.",
    borderRadius: 8,
    fontFamily: undefined,
    light: makePalette(
      "#F0FAF4", "#E6F4EA", "#D1FAE5",
      "#1A3829", "#4A7C59", "#8CAF99",
      "#10B981", "#059669", "#064E3B", "#A7F3D0",
      "#C8E6C9", "#E0F2F1",
      "#15803D", "#B45309", "#B91C1C", "#475569",
      "#D97706",
      "#1A3829", "#059669"
    ),
    dark: makePalette(
      "#0A1F14", "#0F2E1D", "#143D26",
      "#D1FAE5", "#6EE7B7", "#4B5563",
      "#34D399", "#6EE7B7", "#D1FAE5", "#064E3B",
      "#1C3322", "#2A3F30",
      "#4ADE80", "#FBBF24", "#F87171", "#94A3B8",
      "#F59E0B",
      "#D1FAE5", "#6EE7B7"
    ),
  },
  {
    id: "sunrise",
    name: "Sunrise",
    description: "Warm amber and coral with sunrise glow.",
    borderRadius: 8,
    fontFamily: undefined,
    light: makePalette(
      "#FFFBF5", "#FFF7ED", "#FED7AA",
      "#7C3D12", "#A16236", "#C49A7A",
      "#EA580C", "#C2410C", "#7C2D12", "#FFEDD5",
      "#FDE68A", "#FEF3C7",
      "#16A34A", "#CA8A04", "#DC2626", "#475569",
      "#F59E0B",
      "#7C3D12", "#C2410C"
    ),
    dark: makePalette(
      "#1C0D05", "#281506", "#351D08",
      "#FED7AA", "#FDBA74", "#9A3412",
      "#FB923C", "#FDBA74", "#FFEDD5", "#7C2D12",
      "#3D1F0A", "#4A2610",
      "#4ADE80", "#FACC15", "#F87171", "#94A3B8",
      "#FBBF24",
      "#FED7AA", "#FDBA74"
    ),
  },
  {
    id: "arctic",
    name: "Arctic",
    description: "Icy cyan with cool slate neutrals.",
    borderRadius: 4,
    fontFamily: undefined,
    light: makePalette(
      "#F8FAFC", "#F0F9FF", "#E2F8FC",
      "#1E293B", "#475569", "#94A3B8",
      "#06B6D4", "#0891B2", "#164E63", "#A5F3FC",
      "#CBD5E1", "#E2E8F0",
      "#16A34A", "#CA8A04", "#DC2626", "#475569",
      "#F59E0B",
      "#1E293B", "#0891B2"
    ),
    dark: makePalette(
      "#030A12", "#081420", "#0D1E2E",
      "#E2F8FC", "#67E8F9", "#155E75",
      "#22D3EE", "#67E8F9", "#CFFAFE", "#164E63",
      "#0F2936", "#1E3A4D",
      "#4ADE80", "#FACC15", "#F87171", "#94A3B8",
      "#FBBF24",
      "#E2F8FC", "#67E8F9"
    ),
  },
  {
    id: "slate-corporate",
    name: "Slate Corporate",
    description: "Corporate blue-grey for professionals.",
    borderRadius: 4,
    fontFamily: undefined,
    light: makePalette(
      "#F8FAFC", "#F1F5F9", "#E2E8F0",
      "#0F172A", "#475569", "#94A3B8",
      "#2563EB", "#1D4ED8", "#1E3A8A", "#DBEAFE",
      "#CBD5E1", "#E2E8F0",
      "#16A34A", "#CA8A04", "#DC2626", "#475569",
      "#F59E0B",
      "#0F172A", "#1D4ED8"
    ),
    dark: makePalette(
      "#020617", "#0F172A", "#1E293B",
      "#F8FAFC", "#94A3B8", "#475569",
      "#60A5FA", "#93C5FD", "#DBEAFE", "#172554",
      "#1E293B", "#334155",
      "#4ADE80", "#FACC15", "#F87171", "#94A3B8",
      "#FBBF24",
      "#F8FAFC", "#93C5FD"
    ),
  },
  {
    id: "coffee-roast",
    name: "Coffee Roast",
    description: "Warm browns and cream for a cosy feel.",
    borderRadius: 6,
    fontFamily: undefined,
    light: makePalette(
      "#FDF8F3", "#F5EBE0", "#EAD5C0",
      "#3D2305", "#7C5E3F", "#A68B6A",
      "#92400E", "#78350F", "#5C2B0B", "#FED7AA",
      "#EAD5C0", "#F5EBE0",
      "#16A34A", "#CA8A04", "#DC2626", "#475569",
      "#F59E0B",
      "#3D2305", "#78350F"
    ),
    dark: makePalette(
      "#1C1004", "#281705", "#341E07",
      "#F5EBE0", "#C29B70", "#7C5E3F",
      "#C29B70", "#D4B08A", "#F5EBE0", "#3D2305",
      "#341E07", "#4A2A0A",
      "#4ADE80", "#FACC15", "#F87171", "#94A3B8",
      "#FBBF24",
      "#F5EBE0", "#D4B08A"
    ),
  },
  {
    id: "mint-fresh",
    name: "Mint Fresh",
    description: "Cool mint and teal for clarity.",
    borderRadius: 8,
    fontFamily: undefined,
    light: makePalette(
      "#F0FDFA", "#CCFBF1", "#99F6E4",
      "#042F2E", "#115E59", "#2DD4BF",
      "#14B8A6", "#0D9488", "#0F766E", "#CCFBF1",
      "#99F6E4", "#CCFBF1",
      "#16A34A", "#CA8A04", "#DC2626", "#475569",
      "#F59E0B",
      "#042F2E", "#0D9488"
    ),
    dark: makePalette(
      "#021C1B", "#042F2E", "#063E3C",
      "#CCFBF1", "#5EEAD4", "#14B8A6",
      "#2DD4BF", "#5EEAD4", "#CCFBF1", "#042F2E",
      "#063E3C", "#0A504D",
      "#4ADE80", "#FACC15", "#F87171", "#94A3B8",
      "#FBBF24",
      "#CCFBF1", "#5EEAD4"
    ),
  },
  {
    id: "navy-command",
    name: "Navy Command",
    description: "Deep navy enterprise palette.",
    borderRadius: 4,
    fontFamily: undefined,
    light: makePalette(
      "#F0F4F8", "#E2E8F0", "#D9E2EC",
      "#0A192F", "#4A6278", "#8BA3B8",
      "#1E3A5F", "#152D4A", "#0F2240", "#C5D9E8",
      "#CBD5E1", "#E2E8F0",
      "#2E7D5B", "#B45309", "#9B2C2C", "#475569",
      "#D97706",
      "#0A192F", "#152D4A"
    ),
    dark: makePalette(
      "#0A192F", "#0F2240", "#152D4A",
      "#E2E8F0", "#8BA3B8", "#4A6278",
      "#60A5FA", "#93C5FD", "#DBEAFE", "#1E3A5F",
      "#1E3A5F", "#2A3F54",
      "#5BB088", "#E5A553", "#D66060", "#94A3B8",
      "#F59E0B",
      "#E2E8F0", "#93C5FD"
    ),
  },
  {
    id: "bordeaux",
    name: "Bordeaux",
    description: "Deep wine red for sophistication.",
    borderRadius: 6,
    fontFamily: undefined,
    light: makePalette(
      "#FAF5F5", "#F5E7E7", "#EDD5D5",
      "#2D0A0A", "#7C3D3D", "#A66B6B",
      "#7C2D12", "#5C1F0C", "#3D1208", "#F5D5D5",
      "#EDD5D5", "#F5E7E7",
      "#2E7D5B", "#B45309", "#9B2C2C", "#475569",
      "#D97706",
      "#2D0A0A", "#5C1F0C"
    ),
    dark: makePalette(
      "#1A0505", "#2A0A0A", "#3D0F0F",
      "#F5E0E0", "#C27B7B", "#7C3D3D",
      "#D66060", "#E08A8A", "#F5D5D5", "#3D0F0F",
      "#3D0F0F", "#4A1616",
      "#5BB088", "#E5A553", "#F87171", "#94A3B8",
      "#F59E0B",
      "#F5E0E0", "#E08A8A"
    ),
  },
  {
    id: "sandstone",
    name: "Sandstone",
    description: "Warm taupe and architectural beige.",
    borderRadius: 6,
    fontFamily: undefined,
    light: makePalette(
      "#FAF8F5", "#F5F0E8", "#EDE5D8",
      "#2D2926", "#6B6560", "#9C948C",
      "#9C8B7B", "#7C6D5E", "#5C4F42", "#E8DDD0",
      "#DDD5C8", "#E8E0D5",
      "#2E7D5B", "#B45309", "#9B2C2C", "#475569",
      "#D97706",
      "#2D2926", "#7C6D5E"
    ),
    dark: makePalette(
      "#1C1917", "#262320", "#302C28",
      "#E7E2DD", "#9C948C", "#6B6560",
      "#B8A89E", "#CCC0B8", "#DDD8D2", "#302C28",
      "#302C28", "#3A3530",
      "#5BB088", "#E5A553", "#D66060", "#94A3B8",
      "#F59E0B",
      "#E7E2DD", "#CCC0B8"
    ),
  },
  {
    id: "steelworks",
    name: "Steelworks",
    description: "Industrial grey and silver tones.",
    borderRadius: 4,
    fontFamily: undefined,
    light: makePalette(
      "#F5F5F5", "#E8E8E8", "#DBDBDB",
      "#171717", "#525252", "#8A8A8A",
      "#525252", "#404040", "#2A2A2A", "#D4D4D4",
      "#D4D4D4", "#E8E8E8",
      "#2E7D5B", "#B45309", "#9B2C2C", "#475569",
      "#D97706",
      "#171717", "#404040"
    ),
    dark: makePalette(
      "#0A0A0A", "#141414", "#1F1F1F",
      "#E5E5E5", "#A3A3A3", "#525252",
      "#A3A3A3", "#C4C4C4", "#E5E5E5", "#1F1F1F",
      "#1F1F1F", "#2A2A2A",
      "#5BB088", "#E5A553", "#D66060", "#94A3B8",
      "#F59E0B",
      "#E5E5E5", "#C4C4C4"
    ),
  },
  {
    id: "olive-branch",
    name: "Olive Branch",
    description: "Muted olive with natural maturity.",
    borderRadius: 6,
    fontFamily: undefined,
    light: makePalette(
      "#F5F5F0", "#EBEBE0", "#E0E0D0",
      "#1C1C14", "#52523D", "#7C7C5C",
      "#5C5C3D", "#4A4A2E", "#2E2E1C", "#D4D4C0",
      "#D4D4C0", "#E0E0D0",
      "#2E7D5B", "#B45309", "#9B2C2C", "#475569",
      "#D97706",
      "#1C1C14", "#4A4A2E"
    ),
    dark: makePalette(
      "#14140F", "#1C1C14", "#26261C",
      "#E8E8DD", "#A3A37A", "#6B6B4A",
      "#A3A37A", "#B8B890", "#D4D4B8", "#26261C",
      "#26261C", "#303024",
      "#5BB088", "#E5A553", "#D66060", "#94A3B8",
      "#F59E0B",
      "#E8E8DD", "#B8B890"
    ),
  },
  {
    id: "graphite",
    name: "Graphite",
    description: "Dark charcoal for ultra corporate tone.",
    borderRadius: 4,
    fontFamily: undefined,
    light: makePalette(
      "#F8F8F8", "#F0F0F0", "#E5E5E5",
      "#1A1A1A", "#4A4A4A", "#7A7A7A",
      "#404040", "#2E2E2E", "#1A1A1A", "#D9D9D9",
      "#D9D9D9", "#E8E8E8",
      "#2E7D5B", "#B45309", "#9B2C2C", "#475569",
      "#D97706",
      "#1A1A1A", "#2E2E2E"
    ),
    dark: makePalette(
      "#0D0D0D", "#141414", "#1F1F1F",
      "#E8E8E8", "#8A8A8A", "#525252",
      "#737373", "#949494", "#B8B8B8", "#1F1F1F",
      "#1F1F1F", "#2A2A2A",
      "#5BB088", "#E5A553", "#D66060", "#94A3B8",
      "#F59E0B",
      "#E8E8E8", "#949494"
    ),
  },
];

export function getThemeById(
  id: string,
  customThemes?: ThemeDefinition[]
): ThemeDefinition {
  const all = [...PREDEFINED_THEMES, ...(customThemes ?? [])];
  return all.find((t) => t.id === id) ?? PREDEFINED_THEMES[0];
}
