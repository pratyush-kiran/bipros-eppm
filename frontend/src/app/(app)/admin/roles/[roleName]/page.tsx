"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ChevronDown, ChevronRight, ShieldCheck, ArrowLeft, ExternalLink } from "lucide-react";

import { profileApi } from "@/lib/api/profileApi";
import { roleApi } from "@/lib/api/roleApi";
import { userApi } from "@/lib/api/userApi";
import { permissionApi } from "@/lib/api/permissionApi";
import { PageHeader } from "@/components/common/PageHeader";
import { getErrorMessage } from "@/lib/utils/error";
import type { PermissionDescriptor, ProfileResponse, UserResponse } from "@/lib/types";

interface DomainGroup {
  module: string;
  permissions: { code: string; label: string; action: string }[];
}

export default function RoleDetailPage() {
  const params = useParams<{ roleName: string }>();
  const roleName = decodeURIComponent(params?.roleName ?? "");

  const { data: roleData, isLoading: roleLoading, error: roleError } = useQuery({
    queryKey: ["role", roleName],
    queryFn: () => roleApi.get(roleName),
    enabled: !!roleName,
  });

  const { data: profilesData, isLoading: profilesLoading } = useQuery({
    queryKey: ["profiles"],
    queryFn: () => profileApi.listProfiles(),
  });

  const { data: catalogData } = useQuery({
    queryKey: ["permissions-catalog"],
    queryFn: () => permissionApi.list(),
    staleTime: 1000 * 60 * 60,
  });

  const { data: usersData, isLoading: usersLoading } = useQuery({
    queryKey: ["users", "all", "for-roles"],
    queryFn: () => userApi.listUsers(0, 500),
  });

  const role = roleData?.data ?? null;

  const defaultProfile: ProfileResponse | null = useMemo(() => {
    const profiles = profilesData?.data ?? [];
    return (
      profiles.find((p) => p.systemDefault && p.legacyRoleName === roleName) ?? null
    );
  }, [profilesData, roleName]);

  const members: UserResponse[] = useMemo(() => {
    const users: UserResponse[] = usersData?.data?.content ?? [];
    return users.filter((u) => (u.roles ?? []).includes(roleName));
  }, [usersData, roleName]);

  // Index the static catalog by code so we can render human labels next to
  // the bare permission codes the profile carries.
  const catalogByCode = useMemo(() => {
    const map = new Map<string, PermissionDescriptor>();
    for (const p of catalogData?.data ?? []) map.set(p.code, p);
    return map;
  }, [catalogData]);

  const grouped: DomainGroup[] = useMemo(() => {
    if (!defaultProfile) return [];
    const byModule = new Map<string, DomainGroup>();
    for (const code of defaultProfile.permissions) {
      const desc = catalogByCode.get(code);
      const module = desc?.module ?? (code.includes(".") ? code.split(".")[0] : code);
      if (!byModule.has(module)) byModule.set(module, { module, permissions: [] });
      byModule.get(module)!.permissions.push({
        code,
        label: desc?.label ?? code,
        action: desc?.action ?? "",
      });
    }
    return Array.from(byModule.values())
      .map((g) => ({
        module: g.module,
        permissions: g.permissions.sort((a, b) => a.code.localeCompare(b.code)),
      }))
      .sort((a, b) => a.module.localeCompare(b.module));
  }, [defaultProfile, catalogByCode]);

  if (!roleName) {
    return (
      <div className="rounded-xl border border-burgundy/30 bg-burgundy/10 p-4 text-sm text-burgundy">
        No role specified.
      </div>
    );
  }

  if (roleLoading || profilesLoading) {
    return (
      <div className="space-y-4">
        <div className="h-10 w-2/3 animate-pulse rounded bg-parchment" />
        <div className="h-32 animate-pulse rounded bg-parchment" />
        <div className="h-64 animate-pulse rounded bg-parchment" />
      </div>
    );
  }

  if (roleError) {
    return (
      <div className="rounded-md bg-danger/10 p-4 text-sm text-danger">
        {getErrorMessage(roleError, "Failed to load role")}
      </div>
    );
  }

  if (!role) {
    return (
      <div className="rounded-xl border border-dashed border-hairline bg-paper py-12 text-center">
        <p className="text-sm text-slate">Role &quot;{roleName}&quot; not found.</p>
        <Link
          href="/admin/roles"
          className="mt-3 inline-flex items-center gap-1 text-sm font-medium text-gold-deep hover:underline"
        >
          <ArrowLeft size={14} /> Back to roles
        </Link>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div>
        <Link
          href="/admin/roles"
          className="mb-3 inline-flex items-center gap-1 text-xs font-medium text-slate hover:text-gold-deep"
        >
          <ArrowLeft size={12} /> All roles
        </Link>
        <PageHeader
          title={role.name.replace(/_/g, " ")}
          description={role.description}
          actions={
            defaultProfile ? (
              <Link
                href={`/admin/profiles/${encodeURIComponent(defaultProfile.code)}`}
                className="inline-flex items-center gap-1 rounded-md border border-hairline bg-paper px-3 py-2 text-xs font-medium text-charcoal hover:border-gold hover:text-gold-deep"
              >
                Open default profile
                <ExternalLink size={12} />
              </Link>
            ) : null
          }
        />
      </div>

      {/* Members */}
      <section className="rounded-xl border border-hairline bg-paper p-5">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="font-display text-base font-semibold text-charcoal">
            Members
            <span className="ml-2 text-xs font-normal text-slate">
              ({usersLoading ? "…" : members.length})
            </span>
          </h2>
          <Link
            href={`/admin/users?roles=${encodeURIComponent(role.name)}`}
            className="text-xs font-medium text-gold-deep hover:underline"
          >
            Open in Users →
          </Link>
        </div>
        {usersLoading ? (
          <div className="text-xs text-slate">Loading members…</div>
        ) : members.length === 0 ? (
          <p className="text-xs italic text-ash">No users currently hold this role.</p>
        ) : (
          <ul className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-3">
            {members.map((u) => (
              <li
                key={u.id}
                className="flex items-center justify-between gap-2 rounded-md border border-hairline bg-ivory px-3 py-2 text-xs"
              >
                <div className="min-w-0">
                  <div className="truncate font-medium text-charcoal">
                    {(u.firstName || u.lastName)
                      ? `${u.firstName ?? ""} ${u.lastName ?? ""}`.trim()
                      : u.username}
                  </div>
                  <div className="truncate text-[11px] text-slate">{u.email}</div>
                </div>
                <span
                  className={`shrink-0 rounded-full px-1.5 py-0.5 text-[10px] font-semibold ${
                    u.enabled
                      ? "bg-emerald/10 text-emerald"
                      : "bg-burgundy/10 text-burgundy"
                  }`}
                >
                  {u.enabled ? "Active" : "Disabled"}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* Permissions */}
      <section className="rounded-xl border border-hairline bg-paper p-5">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="font-display text-base font-semibold text-charcoal">
            Permissions
            <span className="ml-2 text-xs font-normal text-slate">
              ({defaultProfile?.permissions.length ?? 0} total
              {defaultProfile?.systemDefault ? " · system default" : ""})
            </span>
          </h2>
          {defaultProfile?.systemDefault && (
            <span className="inline-flex items-center gap-1 rounded-full bg-emerald/10 px-2 py-0.5 text-[10px] font-semibold text-emerald">
              <ShieldCheck size={11} />
              Default profile
            </span>
          )}
        </div>
        {!defaultProfile ? (
          <p className="text-sm italic text-ash">
            No system-default profile is mapped to this role yet.
          </p>
        ) : grouped.length === 0 ? (
          <p className="text-sm italic text-ash">This profile has no permissions assigned.</p>
        ) : (
          <div className="space-y-2">
            {grouped.map((g) => (
              <DomainBlock key={g.module} group={g} />
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

function DomainBlock({ group }: { group: DomainGroup }) {
  const [open, setOpen] = useState(true);
  return (
    <div className="rounded-md border border-hairline bg-ivory">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center justify-between gap-2 px-3 py-2 text-left text-xs font-semibold uppercase tracking-wider text-charcoal hover:bg-parchment"
      >
        <span className="inline-flex items-center gap-2">
          {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
          {group.module.replace(/_/g, " ")}
        </span>
        <span className="text-[10px] font-normal text-slate">
          {group.permissions.length}
        </span>
      </button>
      {open && (
        <ul className="divide-y divide-hairline">
          {group.permissions.map((p) => (
            <li
              key={p.code}
              className="flex items-start justify-between gap-3 px-3 py-2 text-xs"
            >
              <div className="min-w-0">
                <div className="font-medium text-charcoal">{p.label}</div>
                <div className="font-mono text-[10px] text-ash">{p.code}</div>
              </div>
              {p.action && (
                <span className="shrink-0 rounded bg-paper px-1.5 py-0.5 text-[10px] font-semibold text-slate">
                  {p.action}
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
