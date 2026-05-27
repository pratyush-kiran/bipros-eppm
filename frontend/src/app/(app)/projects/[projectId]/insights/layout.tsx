"use client";

import { useParams, useRouter, usePathname } from "next/navigation";
import { cn } from "@/lib/utils/cn";

const subTabs = [
  { id: "operational", label: "Operational" },
  { id: "field", label: "Field" },
];

export default function InsightsLayout({ children }: { children: React.ReactNode }) {
  const params = useParams();
  const router = useRouter();
  const pathname = usePathname();
  const projectId = params.projectId as string;

  const activeId = subTabs.find((t) => pathname.endsWith(`/insights/${t.id}`))?.id
    ?? "operational";

  return (
    <div>
      <div className="border-b border-hairline px-6 pt-4 pb-3">
        <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep mb-2">
          Project performance · Insights
        </div>
        <nav className="flex items-center gap-2" aria-label="Insights views">
          {subTabs.map((t) => {
            const isActive = activeId === t.id;
            return (
              <button
                key={t.id}
                type="button"
                onClick={() => router.push(`/projects/${projectId}/insights/${t.id}`)}
                className={cn(
                  "px-3 py-1.5 text-sm font-medium rounded-md transition-colors cursor-pointer",
                  isActive
                    ? "bg-gold-tint/40 text-gold-deep"
                    : "text-text-secondary hover:bg-surface-hover/50 hover:text-text-primary",
                )}
              >
                {t.label}
              </button>
            );
          })}
        </nav>
      </div>
      <div className="px-6 pt-5">{children}</div>
    </div>
  );
}
