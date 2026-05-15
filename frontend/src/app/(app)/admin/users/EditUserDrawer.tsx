"use client";

import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import toast from "react-hot-toast";

import { Drawer } from "@/components/common/Drawer";
import { MultiSelect } from "@/components/common/MultiSelect";
import { profileApi } from "@/lib/api/profileApi";
import { roleApi } from "@/lib/api/roleApi";
import {
  userApi,
  type UpdateUserProfileRequest,
} from "@/lib/api/userApi";
import type {
  Department,
  PresenceStatus,
  UserResponse,
} from "@/lib/types";
import { getErrorMessage } from "@/lib/utils/error";

const DEPARTMENTS: Department[] = [
  "CIVIL",
  "QUALITY",
  "SURVEY",
  "PLANT",
  "HSE",
  "STORES",
  "ADMIN",
  "FINANCE",
  "OTHER",
];

const PRESENCE_STATUSES: PresenceStatus[] = [
  "ON_SITE",
  "ON_LEAVE",
  "TRANSFERRED",
  "RELEASED",
];

interface EditUserDrawerProps {
  open: boolean;
  user: UserResponse | null;
  onClose: () => void;
}

interface FormState {
  email: string;
  firstName: string;
  lastName: string;
  designation: string;
  primaryIcpmsRole: string;
  mobile: string;
  department: Department | "";
  presenceStatus: PresenceStatus | "";
  joiningDate: string;
  contractEndDate: string;
}

const initialForm = (u: UserResponse | null): FormState => ({
  email: u?.email ?? "",
  firstName: u?.firstName ?? "",
  lastName: u?.lastName ?? "",
  designation: u?.designation ?? "",
  primaryIcpmsRole: u?.primaryIcpmsRole ?? "",
  mobile: u?.mobile ?? "",
  department: (u?.department as Department | null | undefined) ?? "",
  presenceStatus: (u?.presenceStatus as PresenceStatus | null | undefined) ?? "",
  joiningDate: u?.joiningDate ?? "",
  contractEndDate: u?.contractEndDate ?? "",
});

const inputCls =
  "mt-1 block w-full rounded-md border border-hairline bg-paper px-3 py-2 text-sm text-charcoal focus:border-gold focus:outline-none focus:ring-1 focus:ring-gold/30 disabled:bg-ivory disabled:text-slate";

const labelCls = "block text-xs font-medium uppercase tracking-wide text-slate";

const sameStringSet = (a: string[], b: string[]) => {
  if (a.length !== b.length) return false;
  const sa = new Set(a);
  return b.every((x) => sa.has(x));
};

export function EditUserDrawer({ open, user, onClose }: EditUserDrawerProps) {
  const queryClient = useQueryClient();

  const [form, setForm] = useState<FormState>(initialForm(user));
  const [roles, setRoles] = useState<string[]>(user?.roles ?? []);
  const [profileId, setProfileId] = useState<string>(user?.profileId ?? "");
  const [saveError, setSaveError] = useState<string | null>(null);

  useEffect(() => {
    setForm(initialForm(user));
    setRoles(user?.roles ?? []);
    setProfileId(user?.profileId ?? "");
    setSaveError(null);
  }, [user]);

  const { data: rolesResp } = useQuery({
    queryKey: ["roles"],
    queryFn: () => roleApi.list(),
    enabled: open,
  });
  const roleOptions = useMemo(
    () =>
      (rolesResp?.data ?? []).map((r) => ({
        value: r.name,
        label: r.name.replace(/_/g, " "),
      })),
    [rolesResp],
  );

  const { data: profilesResp } = useQuery({
    queryKey: ["profiles"],
    queryFn: () => profileApi.listProfiles(),
    enabled: open,
  });
  const profiles = profilesResp?.data ?? [];

  const set = <K extends keyof FormState>(key: K, value: FormState[K]) =>
    setForm((s) => ({ ...s, [key]: value }));

  /**
   * Phase 5.1 — three backend calls fan out from a single Save:
   *  1) PUT /v1/users/{id}              (personnel-master fields)
   *  2) PUT /v1/users/{id}/roles        (only if role set changed)
   *  3) PUT /v1/users/{id}/profile      (only if profile changed)
   *
   * Each is skipped if no diff so we don't gratuitously thrash audit logs.
   */
  const mutation = useMutation({
    mutationFn: async () => {
      if (!user) throw new Error("No user selected");

      const body: UpdateUserProfileRequest = {
        firstName: form.firstName.trim() || null,
        lastName: form.lastName.trim() || null,
        email: form.email.trim() || null,
        mobile: form.mobile.trim() || null,
        designation: form.designation.trim() || null,
        department: form.department || null,
        presenceStatus: form.presenceStatus || null,
        joiningDate: form.joiningDate || null,
        contractEndDate: form.contractEndDate || null,
      };
      await userApi.update(user.id, body);

      const currentRoles = user.roles ?? [];
      if (!sameStringSet(currentRoles, roles)) {
        await userApi.assignRoles(user.id, roles);
      }

      const currentProfile = user.profileId ?? "";
      const nextProfile = profileId || "";
      if (currentProfile !== nextProfile) {
        await userApi.assignProfile(user.id, nextProfile || null);
      }
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["users"] });
      toast.success("User updated");
      onClose();
    },
    onError: (err: unknown) =>
      setSaveError(getErrorMessage(err, "Failed to save user")),
  });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setSaveError(null);
    mutation.mutate();
  };

  return (
    <Drawer
      open={open}
      onClose={onClose}
      title={user ? `Edit ${user.username}` : "Edit user"}
      widthClass="max-w-2xl"
    >
      <form onSubmit={handleSubmit} className="flex h-full flex-col">
        <div className="flex-1 space-y-5 px-5 py-4">
          {saveError && (
            <div className="rounded-md border border-burgundy/30 bg-burgundy/10 px-3 py-2 text-sm text-burgundy">
              {saveError}
            </div>
          )}

          {/* Identity */}
          <section>
            <h3 className="text-[11px] font-semibold uppercase tracking-[0.14em] text-gold-deep">
              Identity
            </h3>
            <div className="mt-3 grid grid-cols-2 gap-3">
              <div>
                <label className={labelCls}>Username</label>
                <input
                  value={user?.username ?? ""}
                  disabled
                  className={inputCls}
                />
              </div>
              <div>
                <label className={labelCls}>Employee Code</label>
                <input
                  value={user?.employeeCode ?? ""}
                  disabled
                  className={inputCls}
                />
              </div>
              <div className="col-span-2">
                <label className={labelCls}>Email</label>
                <input
                  type="email"
                  value={form.email}
                  onChange={(e) => set("email", e.target.value)}
                  className={inputCls}
                />
              </div>
              <div>
                <label className={labelCls}>First name</label>
                <input
                  value={form.firstName}
                  onChange={(e) => set("firstName", e.target.value)}
                  className={inputCls}
                />
              </div>
              <div>
                <label className={labelCls}>Last name</label>
                <input
                  value={form.lastName}
                  onChange={(e) => set("lastName", e.target.value)}
                  className={inputCls}
                />
              </div>
              <div>
                <label className={labelCls}>Designation</label>
                <input
                  value={form.designation}
                  onChange={(e) => set("designation", e.target.value)}
                  className={inputCls}
                  placeholder="e.g. Senior Engineer"
                />
              </div>
              <div>
                <label className={labelCls}>Primary IC-PMS Role</label>
                <input
                  value={form.primaryIcpmsRole}
                  onChange={(e) => set("primaryIcpmsRole", e.target.value)}
                  className={inputCls}
                  placeholder="e.g. SUPERVISOR"
                />
              </div>
              <div>
                <label className={labelCls}>Mobile</label>
                <input
                  value={form.mobile}
                  onChange={(e) => set("mobile", e.target.value)}
                  className={inputCls}
                />
              </div>
              <div>
                <label className={labelCls}>Department</label>
                <select
                  value={form.department}
                  onChange={(e) =>
                    set("department", e.target.value as Department | "")
                  }
                  className={inputCls}
                >
                  <option value="">— None —</option>
                  {DEPARTMENTS.map((d) => (
                    <option key={d} value={d}>
                      {d}
                    </option>
                  ))}
                </select>
              </div>
            </div>
          </section>

          {/* Lifecycle */}
          <section>
            <h3 className="text-[11px] font-semibold uppercase tracking-[0.14em] text-gold-deep">
              Lifecycle
            </h3>
            <div className="mt-3 grid grid-cols-2 gap-3">
              <div>
                <label className={labelCls}>Presence status</label>
                <select
                  value={form.presenceStatus}
                  onChange={(e) =>
                    set(
                      "presenceStatus",
                      e.target.value as PresenceStatus | "",
                    )
                  }
                  className={inputCls}
                >
                  <option value="">— None —</option>
                  {PRESENCE_STATUSES.map((s) => (
                    <option key={s} value={s}>
                      {s.replace("_", " ")}
                    </option>
                  ))}
                </select>
              </div>
              <div />
              <div>
                <label className={labelCls}>Joining date</label>
                <input
                  type="date"
                  value={form.joiningDate}
                  onChange={(e) => set("joiningDate", e.target.value)}
                  className={inputCls}
                />
              </div>
              <div>
                <label className={labelCls}>Contract end date</label>
                <input
                  type="date"
                  value={form.contractEndDate}
                  onChange={(e) => set("contractEndDate", e.target.value)}
                  className={inputCls}
                />
              </div>
            </div>
          </section>

          {/* Access */}
          <section>
            <h3 className="text-[11px] font-semibold uppercase tracking-[0.14em] text-gold-deep">
              Access
            </h3>
            <div className="mt-3 space-y-3">
              <div>
                <label className={labelCls}>
                  Roles ({roles.length} selected)
                </label>
                <MultiSelect
                  options={roleOptions}
                  value={roles}
                  onChange={setRoles}
                  placeholder="Pick one or more roles…"
                  className="mt-1"
                />
                <p className="mt-1 text-xs text-slate">
                  Roles drive page navigation. The Profile below decides which
                  permission set is applied.
                </p>
              </div>
              <div>
                <label className={labelCls}>Permission profile</label>
                <select
                  value={profileId}
                  onChange={(e) => setProfileId(e.target.value)}
                  className={inputCls}
                >
                  <option value="">— No profile —</option>
                  {profiles.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name}
                      {p.systemDefault ? " (default)" : ""}
                    </option>
                  ))}
                </select>
              </div>
            </div>
          </section>
        </div>

        <div className="flex items-center justify-end gap-2 border-t border-hairline bg-ivory px-5 py-3">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-hairline bg-paper px-3 py-1.5 text-sm font-medium text-slate hover:bg-ivory"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={mutation.isPending || !user}
            className="rounded-md bg-gold px-3 py-1.5 text-sm font-semibold text-paper transition-colors hover:bg-gold-deep disabled:opacity-50"
          >
            {mutation.isPending ? "Saving…" : "Save changes"}
          </button>
        </div>
      </form>
    </Drawer>
  );
}
