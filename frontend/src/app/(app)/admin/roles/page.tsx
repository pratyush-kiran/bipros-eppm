"use client";

import Link from "next/link";
import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { ChevronRight, ShieldCheck, Users } from "lucide-react";

import { profileApi } from "@/lib/api/profileApi";
import { roleApi, type RoleDescriptor } from "@/lib/api/roleApi";
import { userApi } from "@/lib/api/userApi";
import { PageHeader } from "@/components/common/PageHeader";
import { getErrorMessage } from "@/lib/utils/error";
import type { ProfileResponse, UserResponse } from "@/lib/types";

interface RoleCardData extends RoleDescriptor {
  profile: ProfileResponse | null;
  memberCount: number;
  domainCounts: { module: string; count: number }[];
  permissionTotal: number;
}

/**
 * Group a profile's permission codes by module prefix and return the top 3
 * domains so the card stays scannable. Codes follow `MODULE.ACTION` so the
 * substring up to the first `.` is the module.
 */
function summariseDomains(permissions: string[]): RoleCardData["domainCounts"] {
  if (permissions.length === 0) return [];
  const counts = new Map<string, number>();
  for (const code of permissions) {
    const idx = code.indexOf(".");
    const mod = idx === -1 ? code : code.slice(0, idx);
    counts.set(mod, (counts.get(mod) ?? 0) + 1);
  }
  return Array.from(counts.entries())
    .map(([module, count]) => ({ module, count }))
    .sort((a, b) => b.count - a.count);
}

export default function RolesPage() {
  const {
    data: rolesData,
    isLoading: rolesLoading,
    error: rolesError,
  } = useQuery({
    queryKey: ["roles"],
    queryFn: () => roleApi.list(),
  });

  const { data: profilesData, isLoading: profilesLoading } = useQuery({
    queryKey: ["profiles"],
    queryFn: () => profileApi.listProfiles(),
  });

  const { data: usersData, isLoading: usersLoading } = useQuery({
    queryKey: ["users", "all", "for-roles"],
    // Pull a big page — the backend default is 50 but admin user counts are
    // small enough that one page covers them. If the list ever grows past
    // 500 this should switch to a server-side aggregate.
    queryFn: () => userApi.listUsers(0, 500),
  });

  const cards: RoleCardData[] = useMemo(() => {
    const roles = rolesData?.data ?? [];
    const profiles = profilesData?.data ?? [];
    const users: UserResponse[] = usersData?.data?.content ?? [];

    // Default profiles are keyed by legacyRoleName + systemDefault=true.
    const defaultByRole = new Map<string, ProfileResponse>();
    for (const p of profiles) {
      if (p.systemDefault) defaultByRole.set(p.legacyRoleName, p);
    }

    // A user "holds" a role when their assigned profile's legacyRoleName matches.
    const memberCountByRole = new Map<string, number>();
    for (const u of users) {
      // Prefer explicit roles[] (legacy JWT field), fall back to profile mapping.
      const roleNames = u.roles?.length ? u.roles : [];
      for (const r of roleNames) {
        memberCountByRole.set(r, (memberCountByRole.get(r) ?? 0) + 1);
      }
    }

    return roles.map((r) => {
      const profile = defaultByRole.get(r.name) ?? null;
      const perms = profile?.permissions ?? [];
      return {
        ...r,
        profile,
        memberCount: memberCountByRole.get(r.name) ?? 0,
        domainCounts: summariseDomains(perms),
        permissionTotal: perms.length,
      };
    });
  }, [rolesData, profilesData, usersData]);

  const loading = rolesLoading || profilesLoading || usersLoading;

  return (
    <div>
      <PageHeader
        title="Roles"
        description="The 22 canonical roles in BIPROS RBAC. Each maps to a system-default permission profile."
      />

      {rolesError && (
        <div className="mb-4 rounded-md bg-danger/10 p-4 text-sm text-danger">
          {getErrorMessage(rolesError, "Failed to load roles")}
        </div>
      )}

      {loading && (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
          {Array.from({ length: 9 }).map((_, i) => (
            <div
              key={i}
              className="h-40 animate-pulse rounded-xl border border-hairline bg-parchment"
            />
          ))}
        </div>
      )}

      {!loading && cards.length > 0 && (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
          {cards.map((c) => (
            <RoleCard key={c.name} card={c} />
          ))}
        </div>
      )}

      {!loading && cards.length === 0 && (
        <div className="rounded-xl border border-dashed border-hairline bg-paper py-12 text-center">
          <p className="text-sm text-slate">No roles available.</p>
        </div>
      )}
    </div>
  );
}

function RoleCard({ card }: { card: RoleCardData }) {
  const topDomains = card.domainCounts.slice(0, 3);
  const moreDomains = card.domainCounts.length - topDomains.length;

  return (
    <Link
      href={`/admin/roles/${encodeURIComponent(card.name)}`}
      className="group relative flex flex-col gap-3 rounded-xl border border-hairline bg-paper p-5 shadow-sm transition-all duration-200 hover:-translate-y-0.5 hover:border-gold hover:shadow-[0_4px_14px_rgba(212,175,55,0.18)]"
    >
      <div className="flex items-start justify-between gap-3">
        <h3 className="font-display text-lg font-semibold leading-tight tracking-tight text-charcoal">
          {card.name.replace(/_/g, " ")}
        </h3>
        <span
          className="inline-flex items-center gap-1 rounded-full bg-gold-tint px-2 py-0.5 text-[11px] font-semibold text-gold-ink"
          title={`${card.memberCount} user${card.memberCount === 1 ? "" : "s"} with this role`}
        >
          <Users size={11} />
          {card.memberCount}
        </span>
      </div>

      <p className="line-clamp-2 text-sm leading-relaxed text-slate">
        {card.description}
      </p>

      <div className="mt-auto flex flex-wrap items-center gap-2 text-[11px] text-slate">
        {card.profile ? (
          topDomains.length > 0 ? (
            <>
              {topDomains.map((d) => (
                <span
                  key={d.module}
                  className="rounded-md border border-hairline bg-ivory px-1.5 py-0.5 font-medium text-charcoal"
                >
                  {d.module.replace(/_/g, " ")}
                  <span className="ml-1 text-ash">{d.count}</span>
                </span>
              ))}
              {moreDomains > 0 && (
                <span className="text-ash">+{moreDomains} more</span>
              )}
            </>
          ) : (
            <span className="text-ash italic">No permissions configured.</span>
          )
        ) : (
          <span className="text-ash italic">No default profile mapped.</span>
        )}
      </div>

      <div className="flex items-center justify-between border-t border-hairline pt-3 text-[11px] font-medium">
        <span className="inline-flex items-center gap-1 text-slate">
          <ShieldCheck size={12} />
          {card.permissionTotal} permission{card.permissionTotal === 1 ? "" : "s"}
        </span>
        <span className="inline-flex items-center gap-1 text-gold-deep opacity-0 transition-opacity group-hover:opacity-100">
          View details
          <ChevronRight size={12} />
        </span>
      </div>
    </Link>
  );
}
