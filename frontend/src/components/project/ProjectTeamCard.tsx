"use client";

import { useMemo } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { ArrowRight, HardHat, Truck, Package, Plus } from "lucide-react";
import { projectResourceApi, type ProjectResourceResponse } from "@/lib/api/projectResourceApi";
import { displayResourceTypeName } from "@/lib/utils/resourceTypeLabel";

interface Props {
  projectId: string;
}

interface TypeBucket {
  label: string;
  count: number;
  icon: React.ComponentType<{ size?: number; className?: string }>;
  color: string;
}

function pickIcon(typeName: string | null | undefined): { icon: TypeBucket["icon"]; color: string } {
  const t = (typeName ?? "").toLowerCase();
  if (t.includes("labor") || t.includes("labour") || t.includes("manpower"))
    return { icon: HardHat, color: "text-blue-500" };
  if (t.includes("equipment") || t.includes("plant"))
    return { icon: Truck, color: "text-amber-500" };
  if (t.includes("material")) return { icon: Package, color: "text-emerald-500" };
  return { icon: Package, color: "text-text-muted" };
}

export function ProjectTeamCard({ projectId }: Props) {
  const { data: poolData, isLoading } = useQuery({
    queryKey: ["resource-pool", projectId],
    queryFn: () => projectResourceApi.listPool(projectId),
  });

  const pool = useMemo<ProjectResourceResponse[]>(() => {
    const raw = poolData?.data as unknown;
    return Array.isArray(raw) ? (raw as ProjectResourceResponse[]) : [];
  }, [poolData]);

  const buckets = useMemo<TypeBucket[]>(() => {
    const counts = new Map<string, number>();
    for (const entry of pool) {
      const key = entry.resourceTypeName ?? "Other";
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    return Array.from(counts.entries())
      .map(([rawLabel, count]) => {
        const { icon, color } = pickIcon(rawLabel);
        return { label: displayResourceTypeName(rawLabel), count, icon, color };
      })
      .sort((a, b) => b.count - a.count);
  }, [pool]);

  const topRoles = useMemo(() => {
    const counts = new Map<string, number>();
    for (const entry of pool) {
      if (!entry.roleName) continue;
      counts.set(entry.roleName, (counts.get(entry.roleName) ?? 0) + 1);
    }
    return Array.from(counts.entries())
      .sort((a, b) => b[1] - a[1])
      .slice(0, 3)
      .map(([name]) => name);
  }, [pool]);

  return (
    <>

      {/* <div className="rounded-xl border border-border bg-surface/50 p-6 shadow-lg">
      <div className="mb-4 flex items-center justify-between">
        <h3 className="text-sm font-medium uppercase tracking-wider text-text-secondary">
          Project Team
        </h3>
        {pool.length > 0 && (
          <Link
            href={`/projects/${projectId}?tab=resources`}
            className="inline-flex items-center gap-1 text-xs font-medium text-accent hover:underline"
          >
            Manage team
            <ArrowRight size={12} />
          </Link>
        )}
      </div>

      {isLoading ? (
        <div className="h-16 animate-pulse rounded-lg bg-surface-hover/50" />
      ) : pool.length === 0 ? (
        <div className="rounded-lg border border-dashed border-border py-6 text-center">
          <p className="text-sm text-text-secondary">
            No team assigned yet. Add the people, equipment, and materials you&apos;ll need for
            this project.
          </p>
          <Link
            href={`/projects/${projectId}?tab=resources`}
            className="mt-3 inline-flex items-center gap-2 rounded-md bg-accent px-3 py-1.5 text-sm font-medium text-accent-foreground hover:bg-accent-hover"
          >
            <Plus size={14} />
            Add your first resources
          </Link>
        </div>
      ) : (
        <>
          <div className="grid grid-cols-3 gap-4">
            {buckets.slice(0, 3).map((b) => {
              const Icon = b.icon;
              return (
                <div
                  key={b.label}
                  className="flex items-center gap-3 rounded-lg border border-border/60 bg-surface px-3 py-3"
                >
                  <Icon size={20} className={b.color} />
                  <div>
                    <p className="text-xs text-text-secondary">{b.label}</p>
                    <p className="text-lg font-semibold text-text-primary">{b.count}</p>
                  </div>
                </div>
              );
            })}
          </div>
          {topRoles.length > 0 && (
            <p className="mt-3 text-xs text-text-secondary">
              <span className="font-medium text-text-secondary/90">Top roles:</span>{" "}
              {topRoles.join(" · ")}
            </p>
          )}
        </>
      )}
    </div> */}
    </>
  );
}
