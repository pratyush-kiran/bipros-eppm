"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import toast from "react-hot-toast";
import { Pencil, Plus, ShieldCheck, Trash2 } from "lucide-react";

import { profileApi } from "@/lib/api/profileApi";
import { userApi } from "@/lib/api/userApi";
import { PageHeader } from "@/components/common/PageHeader";
import { VirtualDataTable } from "@/components/common/VirtualDataTable";
import type { ColumnDef } from "@tanstack/react-table";
import { getErrorMessage } from "@/lib/utils/error";
import type { ProfileResponse, UserResponse } from "@/lib/types";

type Filter = "ALL" | "DEFAULT" | "CUSTOM";

export default function ProfilesPage() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [filter, setFilter] = useState<Filter>("ALL");

  const { data, isLoading, error } = useQuery({
    queryKey: ["profiles"],
    queryFn: () => profileApi.listProfiles(),
  });

  // Join in member counts so admins can see which profiles are actually in
  // use. Falls back to 0 if the user list query is still loading.
  const { data: usersData } = useQuery({
    queryKey: ["users", "all", "for-roles"],
    queryFn: () => userApi.listUsers(0, 500),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => profileApi.deleteProfile(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["profiles"] });
      toast.success("Profile deleted");
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to delete profile"));
    },
  });

  const memberCountByProfileId = useMemo(() => {
    const map = new Map<string, number>();
    const users: UserResponse[] = usersData?.data?.content ?? [];
    for (const u of users) {
      if (!u.profileId) continue;
      map.set(u.profileId, (map.get(u.profileId) ?? 0) + 1);
    }
    return map;
  }, [usersData]);

  const profiles: ProfileResponse[] = useMemo(() => {
    const all = data?.data ?? [];
    if (filter === "DEFAULT") return all.filter((p) => p.systemDefault);
    if (filter === "CUSTOM") return all.filter((p) => !p.systemDefault);
    return all;
  }, [data, filter]);

  const counts = useMemo(() => {
    const all = data?.data ?? [];
    return {
      total: all.length,
      system: all.filter((p) => p.systemDefault).length,
      custom: all.filter((p) => !p.systemDefault).length,
    };
  }, [data]);

  const columns = useMemo<ColumnDef<ProfileResponse>[]>(
    () => [
      {
        accessorKey: "name",
        header: "Profile name",
        enableSorting: true,
        cell: (info) => {
          const row = info.row.original;
          return (
            <div className="flex flex-col">
              <button
                onClick={() =>
                  router.push(`/admin/profiles/${encodeURIComponent(row.code)}`)
                }
                className="text-left font-medium text-charcoal hover:text-gold-deep"
              >
                {row.name}
              </button>
              <span className="text-xs text-ash">{row.code}</span>
            </div>
          );
        },
      },
      {
        accessorKey: "legacyRoleName",
        header: "Legacy role",
        cell: (info) => (
          <Link
            href={`/admin/roles/${encodeURIComponent(info.row.original.legacyRoleName)}`}
            className="inline-block rounded-full bg-gold-tint px-2 py-0.5 text-xs font-semibold text-gold-ink hover:bg-gold/30"
          >
            {info.row.original.legacyRoleName}
          </Link>
        ),
      },
      {
        accessorKey: "permissions",
        header: "Permission count",
        cell: (info) => (
          <span className="inline-block rounded-full bg-ivory px-2 py-0.5 text-xs font-medium text-charcoal">
            {info.row.original.permissions.length}
          </span>
        ),
      },
      {
        accessorKey: "systemDefault",
        header: "System default?",
        cell: (info) => {
          const value = info.getValue<boolean>();
          return value ? (
            <span className="inline-flex items-center gap-1 rounded-full bg-emerald/10 px-2 py-0.5 text-xs font-medium text-emerald">
              <ShieldCheck size={12} /> Yes
            </span>
          ) : (
            <span className="text-xs text-slate">No</span>
          );
        },
      },
      {
        id: "memberCount",
        header: "Members",
        cell: (info) => {
          const count = memberCountByProfileId.get(info.row.original.id) ?? 0;
          return (
            <span className="inline-block rounded-full bg-ivory px-2 py-0.5 text-xs text-charcoal">
              {count}
            </span>
          );
        },
      },
      {
        id: "actions",
        header: "",
        cell: (info) => {
          const row = info.row.original;
          return (
            <div className="flex items-center justify-end gap-1">
              <button
                onClick={() =>
                  router.push(`/admin/profiles/${encodeURIComponent(row.code)}`)
                }
                className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs font-medium text-gold-deep hover:bg-parchment"
                title="Edit profile"
              >
                <Pencil size={14} /> Edit
              </button>
              {!row.systemDefault && (
                <button
                  disabled={deleteMutation.isPending}
                  onClick={() => {
                    if (window.confirm(`Delete profile "${row.name}"?`)) {
                      deleteMutation.mutate(row.id);
                    }
                  }}
                  className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs font-medium text-burgundy hover:bg-burgundy/10 disabled:opacity-40"
                  title="Delete profile"
                >
                  <Trash2 size={14} /> Delete
                </button>
              )}
            </div>
          );
        },
      },
    ],
    [router, deleteMutation, memberCountByProfileId],
  );

  return (
    <div>
      <PageHeader
        title="Permission Profiles"
        description="Each user is assigned exactly one profile. Profiles bundle the actions a user can perform across the application."
        actions={
          <Link
            href="/admin/profiles/new"
            className="inline-flex items-center gap-2 rounded-md bg-gold px-3 py-2 text-sm font-semibold text-paper transition-all hover:bg-gold-deep hover:shadow-[0_4px_14px_rgba(212,175,55,0.3)]"
          >
            <Plus size={16} /> Create profile
          </Link>
        }
      />

      {/* Filter toolbar */}
      <div className="mb-4 flex flex-wrap items-center gap-2">
        {([
          { key: "ALL", label: `All (${counts.total})` },
          { key: "DEFAULT", label: `System default (${counts.system})` },
          { key: "CUSTOM", label: `Custom (${counts.custom})` },
        ] as { key: Filter; label: string }[]).map((t) => (
          <button
            key={t.key}
            type="button"
            onClick={() => setFilter(t.key)}
            className={`rounded-[10px] px-3.5 py-1.5 text-sm font-medium transition-colors ${
              filter === t.key
                ? "bg-gold text-paper shadow-[0_4px_14px_rgba(212,175,55,0.3)]"
                : "border border-hairline bg-paper text-charcoal hover:bg-ivory"
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {error && (
        <div className="mb-4 rounded-md bg-danger/10 p-4 text-sm text-danger">
          {getErrorMessage(error, "Failed to load profiles")}
        </div>
      )}

      <div className="rounded-xl border border-hairline bg-paper shadow-sm">
        {isLoading ? (
          <div className="p-8 text-center text-sm text-slate">Loading profiles…</div>
        ) : profiles.length === 0 ? (
          <div className="py-12 text-center text-sm italic text-ash">
            No profiles match this filter.
          </div>
        ) : (
          <VirtualDataTable columns={columns} data={profiles} />
        )}
      </div>
    </div>
  );
}
