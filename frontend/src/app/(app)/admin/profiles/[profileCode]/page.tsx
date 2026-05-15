"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import toast from "react-hot-toast";
import { ArrowLeft, ShieldCheck, Trash2 } from "lucide-react";

import { profileApi } from "@/lib/api/profileApi";
import { permissionApi } from "@/lib/api/permissionApi";
import { PageHeader } from "@/components/common/PageHeader";
import { getErrorMessage } from "@/lib/utils/error";
import type {
  PermissionDescriptor,
  ProfileResponse,
  UpdateProfileRequest,
} from "@/lib/types";

import { CANONICAL_ROLES } from "@/lib/api/roleApi";

/**
 * Profile detail / edit page keyed by the human-readable `code`.
 *
 * - System defaults: description-only edit (name, role mapping, and permissions
 *   are read-only — those values come from {@code RolePermissionMatrix} and a
 *   support engineer would otherwise change them ad-hoc per environment).
 * - Custom profiles: name + description + permissions are all editable.
 *   Legacy role mapping stays read-only post-creation to keep JWT semantics
 *   stable for users already assigned the profile.
 */
export default function ProfileDetailPage() {
  const params = useParams<{ profileCode: string }>();
  const router = useRouter();
  const queryClient = useQueryClient();
  const code = decodeURIComponent(params?.profileCode ?? "");

  const { data, isLoading, error } = useQuery({
    queryKey: ["profiles"],
    queryFn: () => profileApi.listProfiles(),
  });

  const { data: catalogData } = useQuery({
    queryKey: ["permissions-catalog"],
    queryFn: () => permissionApi.list(),
    staleTime: 1000 * 60 * 60,
  });

  const profile: ProfileResponse | null = useMemo(() => {
    return (data?.data ?? []).find((p) => p.code === code) ?? null;
  }, [data, code]);

  // Form state — initialised from the fetched profile.
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [permissions, setPermissions] = useState<Set<string>>(new Set());
  const [openModules, setOpenModules] = useState<Set<string>>(new Set());

  useEffect(() => {
    if (!profile) return;
    setName(profile.name);
    setDescription(profile.description ?? "");
    setPermissions(new Set(profile.permissions));
  }, [profile]);

  const grouped = useMemo(() => {
    const all: PermissionDescriptor[] = catalogData?.data ?? [];
    const map = new Map<string, PermissionDescriptor[]>();
    for (const p of all) {
      if (!map.has(p.module)) map.set(p.module, []);
      map.get(p.module)!.push(p);
    }
    return Array.from(map.entries()).sort(([a], [b]) => a.localeCompare(b));
  }, [catalogData]);

  const togglePermission = (permCode: string) =>
    setPermissions((prev) => {
      const next = new Set(prev);
      if (next.has(permCode)) next.delete(permCode);
      else next.add(permCode);
      return next;
    });

  const toggleModule = (perms: PermissionDescriptor[], select: boolean) =>
    setPermissions((prev) => {
      const next = new Set(prev);
      perms.forEach((p) => (select ? next.add(p.code) : next.delete(p.code)));
      return next;
    });

  const toggleSection = (mod: string) =>
    setOpenModules((prev) => {
      const next = new Set(prev);
      if (next.has(mod)) next.delete(mod);
      else next.add(mod);
      return next;
    });

  const saveMutation = useMutation({
    mutationFn: () => {
      if (!profile) throw new Error("No profile loaded");
      const body: UpdateProfileRequest = profile.systemDefault
        ? { description }
        : {
            name,
            description,
            permissions: Array.from(permissions),
          };
      return profileApi.updateProfile(profile.id, body);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["profiles"] });
      toast.success("Profile updated");
    },
    onError: (err: unknown) =>
      toast.error(getErrorMessage(err, "Failed to update profile")),
  });

  const deleteMutation = useMutation({
    mutationFn: () => {
      if (!profile) throw new Error("No profile loaded");
      return profileApi.deleteProfile(profile.id);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["profiles"] });
      toast.success("Profile deleted");
      router.push("/admin/profiles");
    },
    onError: (err: unknown) =>
      toast.error(getErrorMessage(err, "Failed to delete profile")),
  });

  if (isLoading) {
    return (
      <div className="space-y-4">
        <div className="h-10 w-2/3 animate-pulse rounded bg-parchment" />
        <div className="h-64 animate-pulse rounded bg-parchment" />
      </div>
    );
  }

  if (error) {
    return (
      <div className="rounded-md bg-danger/10 p-4 text-sm text-danger">
        {getErrorMessage(error, "Failed to load profile")}
      </div>
    );
  }

  if (!profile) {
    return (
      <div className="rounded-xl border border-dashed border-hairline bg-paper py-12 text-center">
        <p className="text-sm text-slate">Profile &quot;{code}&quot; not found.</p>
        <Link
          href="/admin/profiles"
          className="mt-3 inline-flex items-center gap-1 text-sm font-medium text-gold-deep hover:underline"
        >
          <ArrowLeft size={14} /> Back to profiles
        </Link>
      </div>
    );
  }

  const isSystem = profile.systemDefault;
  const totalCatalog = catalogData?.data?.length ?? 0;
  const roleExists = CANONICAL_ROLES.some((r) => r.name === profile.legacyRoleName);

  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <div>
        <Link
          href="/admin/profiles"
          className="mb-3 inline-flex items-center gap-1 text-xs font-medium text-slate hover:text-gold-deep"
        >
          <ArrowLeft size={12} /> All profiles
        </Link>
        <PageHeader
          title={profile.name}
          description={
            isSystem
              ? "System default — name, role mapping, and permissions are read-only. You can still edit the description."
              : "Custom profile — fully editable. Changes apply to all users with this profile."
          }
          actions={
            isSystem ? (
              <span className="inline-flex items-center gap-1 rounded-full bg-emerald/10 px-2.5 py-1 text-[11px] font-semibold text-emerald">
                <ShieldCheck size={12} /> System default
              </span>
            ) : (
              <button
                type="button"
                disabled={deleteMutation.isPending}
                onClick={() => {
                  if (window.confirm(`Delete profile "${profile.name}"?`)) {
                    deleteMutation.mutate();
                  }
                }}
                className="inline-flex items-center gap-1 rounded-md border border-burgundy/40 bg-paper px-3 py-2 text-xs font-medium text-burgundy hover:bg-burgundy/10 disabled:opacity-50"
              >
                <Trash2 size={14} /> Delete profile
              </button>
            )
          }
        />
      </div>

      {/* Identity block */}
      <section className="space-y-4 rounded-xl border border-hairline bg-paper p-5">
        <h2 className="text-sm font-semibold text-charcoal">Profile details</h2>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label="Code" hint="Immutable identifier.">
            <input
              value={profile.code}
              disabled
              className="block w-full rounded-md border border-hairline bg-ivory px-3 py-2 font-mono text-sm uppercase text-charcoal disabled:opacity-70"
            />
          </Field>
          <Field
            label="Legacy role"
            hint={
              roleExists
                ? "Maps users to this canonical role in their JWT."
                : "Maps users to a canonical role. (This value is not in the 22-role catalog.)"
            }
          >
            <input
              value={profile.legacyRoleName}
              disabled
              className="block w-full rounded-md border border-hairline bg-ivory px-3 py-2 text-sm text-charcoal disabled:opacity-70"
            />
          </Field>
          <Field label="Name" hint={isSystem ? "Read-only (system default)." : "Display name."}>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              disabled={isSystem}
              className="block w-full rounded-md border border-hairline bg-paper px-3 py-2 text-sm text-charcoal focus:border-gold focus:outline-none disabled:bg-ivory disabled:opacity-70"
            />
          </Field>
          <Field label="Description" hint="Editable on system defaults too.">
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={2}
              className="block w-full rounded-md border border-hairline bg-paper px-3 py-2 text-sm text-charcoal focus:border-gold focus:outline-none"
            />
          </Field>
        </div>
      </section>

      {/* Permission picker */}
      <section className="space-y-4 rounded-xl border border-hairline bg-paper p-5">
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-sm font-semibold text-charcoal">Permissions</h2>
            <p className="text-xs text-slate">
              {permissions.size} of {totalCatalog} selected
              {isSystem && " · read-only (system default)"}
            </p>
          </div>
        </div>

        <div className="space-y-2">
          {grouped.map(([moduleName, perms]) => {
            const open = openModules.has(moduleName);
            const allSelected = perms.every((p) => permissions.has(p.code));
            const someSelected = perms.some((p) => permissions.has(p.code));
            const selectedCount = perms.filter((p) => permissions.has(p.code)).length;
            return (
              <div
                key={moduleName}
                className="overflow-hidden rounded-md border border-hairline bg-ivory"
              >
                <div className="flex items-center justify-between gap-2 px-3 py-2">
                  <button
                    type="button"
                    onClick={() => toggleSection(moduleName)}
                    className="flex flex-1 items-center justify-between gap-2 text-left text-xs font-semibold uppercase tracking-wider text-charcoal"
                  >
                    <span>{moduleName.replace(/_/g, " ")}</span>
                    <span className="text-[11px] font-normal text-slate">
                      {selectedCount}/{perms.length} {open ? "▾" : "▸"}
                    </span>
                  </button>
                  {!isSystem && (
                    <button
                      type="button"
                      onClick={() => toggleModule(perms, !allSelected)}
                      className={`shrink-0 text-[11px] font-medium hover:underline ${
                        allSelected
                          ? "text-gold-deep"
                          : someSelected
                          ? "text-bronze-warn"
                          : "text-slate"
                      }`}
                    >
                      {allSelected ? "Unselect all" : "Select all"}
                    </button>
                  )}
                </div>
                {open && (
                  <div className="border-t border-hairline bg-paper px-3 py-2">
                    <div className="grid grid-cols-1 gap-1.5 sm:grid-cols-2">
                      {perms.map((p) => {
                        const checked = permissions.has(p.code);
                        return (
                          <label
                            key={p.code}
                            className={`flex cursor-pointer items-start gap-2 rounded px-2 py-1.5 text-xs ${
                              isSystem ? "cursor-default" : "hover:bg-ivory"
                            }`}
                          >
                            <input
                              type="checkbox"
                              checked={checked}
                              disabled={isSystem}
                              onChange={() => togglePermission(p.code)}
                              className="mt-0.5 h-3.5 w-3.5 cursor-pointer rounded border-hairline text-gold focus:ring-gold disabled:cursor-not-allowed disabled:opacity-70"
                            />
                            <span className="flex-1">
                              <span className="block font-medium text-charcoal">{p.label}</span>
                              <span className="block font-mono text-[10px] text-ash">{p.code}</span>
                            </span>
                          </label>
                        );
                      })}
                    </div>
                  </div>
                )}
              </div>
            );
          })}
          {grouped.length === 0 && (
            <p className="text-xs italic text-ash">Permission catalog not loaded.</p>
          )}
        </div>
      </section>

      <div className="flex items-center justify-end gap-2">
        <Link
          href="/admin/profiles"
          className="rounded-md border border-hairline bg-paper px-4 py-2 text-sm font-medium text-charcoal hover:bg-ivory"
        >
          Cancel
        </Link>
        <button
          type="button"
          disabled={saveMutation.isPending}
          onClick={() => saveMutation.mutate()}
          className="inline-flex items-center gap-1 rounded-md bg-gold px-4 py-2 text-sm font-semibold text-paper transition-all hover:bg-gold-deep disabled:opacity-50"
        >
          {saveMutation.isPending
            ? "Saving…"
            : isSystem
            ? "Save description"
            : "Save changes"}
        </button>
      </div>
    </div>
  );
}

function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <div>
      <label className="block text-xs font-semibold uppercase tracking-wider text-slate">
        {label}
      </label>
      <div className="mt-1">{children}</div>
      {hint && <p className="mt-1 text-[11px] text-ash">{hint}</p>}
    </div>
  );
}
