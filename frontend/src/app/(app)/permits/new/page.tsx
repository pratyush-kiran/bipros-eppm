"use client";

import { useState, Suspense } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Plus, Trash2 } from "lucide-react";
import { WizardShell } from "@/components/common/wizard";
import { SearchableSelect } from "@/components/common/SearchableSelect";
import { PermitTypeBadge, RiskBadge } from "@/components/permits";
import {
  permitApi,
  type CreatePermitRequest,
  type PermitTypeTemplate,
  type PpeItemTemplate,
  type RiskLevel,
  type WorkShift,
  type WorkerRole,
} from "@/lib/api/permitApi";
import { projectApi } from "@/lib/api/projectApi";
import { useAuthStore } from "@/lib/state/store";

interface WorkerDraft {
  fullName: string;
  civilId: string;
  nationality: string;
  trade: string;
  roleOnPermit: WorkerRole;
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const isUuid = (s: string) => UUID_RE.test(s.trim());

const EMPTY_WORKER: WorkerDraft = {
  fullName: "",
  civilId: "",
  nationality: "",
  trade: "",
  roleOnPermit: "PRINCIPAL",
};

export default function NewPermitPage() {
  return (
    <Suspense fallback={<div className="p-6 text-sm text-slate">Loading…</div>}>
      <NewPermitPageInner />
    </Suspense>
  );
}

function NewPermitPageInner() {
  const router = useRouter();
  const search = useSearchParams();
  const projectIdParam = search.get("projectId") || "";
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canCreatePermit = hasPermission("PERMIT.CREATE");
  const queryClient = useQueryClient();

  const [projectId, setProjectId] = useState(projectIdParam);
  const [packCode, setPackCode] = useState<string>("ROAD");
  const [typeId, setTypeId] = useState<string>("");
  const [workers, setWorkers] = useState<WorkerDraft[]>([{ ...EMPTY_WORKER }]);
  const [contractorOrgId, setContractorOrgId] = useState<string>("");
  const [supervisorName, setSupervisorName] = useState<string>("");
  const [locationZone, setLocationZone] = useState<string>("");
  const [chainageMarker, setChainageMarker] = useState<string>("");
  const [startAt, setStartAt] = useState<string>(() => new Date().toISOString().slice(0, 16));
  const [endAt, setEndAt] = useState<string>(() =>
    new Date(Date.now() + 8 * 3600_000).toISOString().slice(0, 16)
  );
  const [shift, setShift] = useState<WorkShift>("DAY");
  const [riskLevel, setRiskLevel] = useState<RiskLevel>("MEDIUM");
  const [taskDescription, setTaskDescription] = useState<string>("");
  const [confirmedPpe, setConfirmedPpe] = useState<Set<string>>(new Set());
  const [declarationAccepted, setDeclarationAccepted] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  const { data: packs } = useQuery({
    queryKey: ["permit-packs"],
    queryFn: () => permitApi.listPacks(),
    staleTime: 5 * 60_000,
  });

  const { data: projectsData } = useQuery({
    queryKey: ["projects", "permit-picker"],
    queryFn: () => projectApi.listProjects(0, 500),
    staleTime: 60_000,
  });
  const projectOptions = (projectsData?.data?.content ?? []).map((p) => ({
    value: p.id,
    label: p.code ? `${p.code} — ${p.name}` : p.name,
  }));
  const selectedProject = projectsData?.data?.content?.find((p) => p.id === projectId);

  const { data: types = [], isLoading: typesLoading } = useQuery({
    queryKey: ["permit-pack-types", packCode],
    queryFn: () => (packCode ? permitApi.listTypesForPack(packCode) : Promise.resolve([])),
    enabled: !!packCode,
  });

  const { data: ppeItems = [] } = useQuery({
    queryKey: ["permit-type-ppe", typeId],
    queryFn: () => (typeId ? permitApi.ppeItemsForType(typeId) : Promise.resolve([])),
    enabled: !!typeId,
  });

  const selectedType: PermitTypeTemplate | undefined = types.find((t) => t.id === typeId);

  // When a type is picked, prefill defaults from its template.
  const onPickType = (t: PermitTypeTemplate) => {
    setTypeId(t.id);
    setRiskLevel(t.defaultRiskLevel);
  };

  const updateWorker = (i: number, patch: Partial<WorkerDraft>) =>
    setWorkers((ws) => ws.map((w, idx) => (idx === i ? { ...w, ...patch } : w)));
  const addWorker = () => setWorkers((ws) => [...ws, { ...EMPTY_WORKER, roleOnPermit: "HELPER" }]);
  const removeWorker = (i: number) =>
    setWorkers((ws) => (ws.length > 1 ? ws.filter((_, idx) => idx !== i) : ws));

  const togglePpe = (id: string) =>
    setConfirmedPpe((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const validate = (step: number): boolean => {
    setError(null);
    if (step === 0) {
      if (!projectId) {
        setError("Please select a project.");
        return false;
      }
      if (!typeId) {
        setError("Please select a permit type.");
        return false;
      }
      if (!workers[0].fullName.trim()) {
        setError("At least one worker (full name) is required.");
        return false;
      }
      if (contractorOrgId.trim() && !isUuid(contractorOrgId)) {
        setError("Contractor / Org ID must be a valid UUID, or leave it blank.");
        return false;
      }
    }
    if (step === 1) {
      if (!locationZone.trim()) {
        setError("Work location / zone is required.");
        return false;
      }
      if (!new Date(startAt).getTime() || !new Date(endAt).getTime()) {
        setError("Start and end times are required.");
        return false;
      }
      if (new Date(endAt) <= new Date(startAt)) {
        setError("End time must be after start time.");
        return false;
      }
      if (!taskDescription.trim()) {
        setError("Task description is required.");
        return false;
      }
    }
    if (step === 2) {
      if (!declarationAccepted) {
        setError("You must accept the declaration before continuing.");
        return false;
      }
    }
    return true;
  };

  const submit = useMutation({
    mutationFn: () => {
      if (!projectId || !typeId) throw new Error("Missing required fields");
      const body: CreatePermitRequest = {
        permitTypeTemplateId: typeId,
        riskLevel,
        contractorOrgId: isUuid(contractorOrgId) ? contractorOrgId.trim() : null,
        supervisorName: supervisorName || null,
        locationZone,
        chainageMarker: chainageMarker || null,
        startAt: new Date(startAt).toISOString(),
        endAt: new Date(endAt).toISOString(),
        shift,
        taskDescription,
        workers: workers
          .filter((w) => w.fullName.trim())
          .map((w) => ({
            fullName: w.fullName,
            civilId: w.civilId || undefined,
            nationality: w.nationality || undefined,
            trade: w.trade || undefined,
            roleOnPermit: w.roleOnPermit,
          })),
        confirmedPpeItemIds: Array.from(confirmedPpe),
        declarationAccepted,
      };
      return permitApi.create(projectId, body);
    },
    onSuccess: (created) => {
      queryClient.invalidateQueries({ queryKey: ["permits-list"] });
      queryClient.invalidateQueries({ queryKey: ["permits-dashboard"] });
      router.push(`/permits/${created.id}`);
    },
    onError: (err: Error) => setError(err.message),
  });

  const onSubmit = async () => {
    await submit.mutateAsync();
  };

  if (!canCreatePermit) {
    return (
      <div className="space-y-6 p-6">
        <header className="flex items-center gap-3">
          <Link
            href="/permits"
            className="inline-flex items-center gap-1 rounded-md border border-divider bg-paper px-3 py-1.5 text-sm text-slate hover:bg-ivory"
          >
            <ArrowLeft size={14} /> Back
          </Link>
        </header>
        <div className="rounded-md border border-divider bg-paper p-6 text-sm text-slate">
          You don&apos;t have permission to raise a new permit. Required permission:{" "}
          <code className="font-mono">PERMIT.CREATE</code>. Ask an admin to assign a profile that
          includes this permission.
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6 p-6">
      <header className="flex items-center gap-3">
        <Link
          href="/permits"
          className="inline-flex items-center gap-1 rounded-md border border-divider bg-paper px-3 py-1.5 text-sm text-slate hover:bg-ivory"
        >
          <ArrowLeft size={14} /> Back
        </Link>
        <div>
          <p className="text-xs uppercase tracking-widest text-slate">New Work Permit Application</p>
          <h1 className="text-2xl font-bold text-charcoal">Create Permit-to-Work</h1>
        </div>
      </header>

      <div>
        <label className="text-xs font-semibold uppercase tracking-wider text-slate">
          Project
        </label>
        <SearchableSelect
          className="mt-1"
          value={projectId}
          onChange={setProjectId}
          options={projectOptions}
          placeholder="Search and select a project…"
        />
      </div>

      <WizardShell
        steps={[
          { key: "type", label: "Permit Type & Worker" },
          { key: "task", label: "Task & Location" },
          { key: "ppe", label: "PPE & Safety" },
          { key: "review", label: "Review & Submit" },
        ]}
        validateStep={validate}
        onSubmit={onSubmit}
        submitting={submit.isPending}
      >
        {/* Step 1 */}
        <div className="space-y-5">
          <section>
            <label className="text-xs font-semibold uppercase tracking-wider text-slate">
              Industry Pack
            </label>
            <div className="mt-2 flex flex-wrap gap-2">
              {packs?.map((p) => (
                <button
                  key={p.id}
                  type="button"
                  onClick={() => {
                    setPackCode(p.code);
                    setTypeId("");
                  }}
                  className={`rounded-full border px-3 py-1 text-xs font-semibold transition ${
                    packCode === p.code
                      ? "border-gold bg-gold-tint text-gold-ink"
                      : "border-divider bg-paper text-slate hover:border-gold/30"
                  }`}
                >
                  {p.name}
                </button>
              ))}
            </div>
          </section>

          <section>
            <label className="text-xs font-semibold uppercase tracking-wider text-slate">
              Select Permit Type *
            </label>
            <div className="mt-2 grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-4">
              {typesLoading && <p className="text-sm text-slate">Loading…</p>}
              {types.map((t) => {
                const active = typeId === t.id;
                return (
                  <button
                    key={t.id}
                    type="button"
                    onClick={() => onPickType(t)}
                    className={`rounded-lg border bg-paper p-3 text-left transition hover:shadow-sm ${
                      active
                        ? "border-2 shadow"
                        : "border-divider"
                    }`}
                    style={active ? { borderColor: t.colorHex || "#D4AF37" } : undefined}
                  >
                    <PermitTypeBadge code={t.code} name={t.name} colorHex={t.colorHex} />
                    <div className="mt-2 text-xs text-slate">
                      Default risk: {t.defaultRiskLevel} · Max {t.maxDurationHours}h
                    </div>
                  </button>
                );
              })}
            </div>
          </section>

          <section>
            <div className="flex items-center justify-between">
              <label className="text-xs font-semibold uppercase tracking-wider text-slate">
                Workers *
              </label>
              <button
                type="button"
                onClick={addWorker}
                className="inline-flex items-center gap-1 rounded-md border border-divider bg-paper px-3 py-1 text-xs font-semibold text-charcoal hover:bg-ivory"
              >
                <Plus size={12} /> Add worker
              </button>
            </div>
            <div className="mt-2 space-y-3">
              {workers.map((w, i) => (
                <div
                  key={i}
                  className="grid grid-cols-1 gap-2 rounded-lg border border-hairline bg-ivory/40 p-3 sm:grid-cols-2 lg:grid-cols-5"
                >
                  <FieldInput
                    label="Full name *"
                    value={w.fullName}
                    onChange={(v) => updateWorker(i, { fullName: v })}
                  />
                  <FieldInput
                    label="Civil ID / Passport"
                    value={w.civilId}
                    onChange={(v) => updateWorker(i, { civilId: v })}
                  />
                  <FieldInput
                    label="Nationality"
                    value={w.nationality}
                    onChange={(v) => updateWorker(i, { nationality: v })}
                  />
                  <FieldInput
                    label="Trade"
                    value={w.trade}
                    onChange={(v) => updateWorker(i, { trade: v })}
                  />
                  <div>
                    <label className="text-[11px] font-semibold uppercase tracking-wider text-slate">
                      Role
                    </label>
                    <select
                      value={w.roleOnPermit}
                      onChange={(e) =>
                        updateWorker(i, { roleOnPermit: e.target.value as WorkerRole })
                      }
                      className="mt-1 block w-full rounded-md border border-divider bg-paper px-2 py-1.5 text-sm text-charcoal"
                    >
                      <option value="PRINCIPAL">Principal</option>
                      <option value="HELPER">Helper</option>
                      <option value="FIRE_WATCH">Fire Watch</option>
                      <option value="STANDBY">Standby</option>
                    </select>
                  </div>
                  {workers.length > 1 && (
                    <button
                      type="button"
                      onClick={() => removeWorker(i)}
                      className="col-span-full inline-flex items-center justify-end gap-1 text-xs font-semibold text-burgundy"
                    >
                      <Trash2 size={12} /> Remove
                    </button>
                  )}
                </div>
              ))}
            </div>
          </section>

          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <FieldInput
              label="Contractor / Org ID (UUID, optional)"
              value={contractorOrgId}
              onChange={setContractorOrgId}
              placeholder="e.g. 33cd0717-99ac-4be3-b589-8d5892341b17"
            />
            <FieldInput label="Supervisor Name" value={supervisorName} onChange={setSupervisorName} />
          </div>
        </div>

        {/* Step 2 */}
        <div className="space-y-4">
          <h3 className="text-sm font-semibold text-charcoal">Step 2 — Task &amp; Location</h3>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <FieldInput
              label="Work Location / Zone *"
              value={locationZone}
              onChange={setLocationZone}
              placeholder="e.g. Zone D — Bridge Approach"
            />
            <FieldInput
              label="Chainage / KM Marker"
              value={chainageMarker}
              onChange={setChainageMarker}
              placeholder="e.g. Km 14.2"
            />
            <DateTimeInput label="Start *" value={startAt} onChange={setStartAt} />
            <DateTimeInput label="End *" value={endAt} onChange={setEndAt} />
            <div>
              <label className="text-xs font-semibold uppercase tracking-wider text-slate">
                Shift
              </label>
              <select
                value={shift}
                onChange={(e) => setShift(e.target.value as WorkShift)}
                className="mt-1 block w-full rounded-md border border-divider bg-paper px-3 py-2 text-sm text-charcoal"
              >
                <option value="DAY">Day (06:00–18:00)</option>
                <option value="NIGHT">Night (18:00–06:00)</option>
                <option value="SPLIT">Split</option>
              </select>
            </div>
            <div>
              <label className="text-xs font-semibold uppercase tracking-wider text-slate">
                Risk Level *
              </label>
              <div className="mt-1 flex gap-2">
                {(["LOW", "MEDIUM", "HIGH"] as RiskLevel[]).map((r) => (
                  <button
                    key={r}
                    type="button"
                    onClick={() => setRiskLevel(r)}
                    className={`flex-1 rounded-md border px-2 py-1.5 text-xs font-semibold ${
                      riskLevel === r
                        ? "border-gold bg-gold-tint"
                        : "border-divider bg-paper text-slate hover:border-gold/30"
                    }`}
                  >
                    <RiskBadge level={r} />
                  </button>
                ))}
              </div>
            </div>
          </div>
          <div>
            <label className="text-xs font-semibold uppercase tracking-wider text-slate">
              Task Description *
            </label>
            <textarea
              value={taskDescription}
              onChange={(e) => setTaskDescription(e.target.value)}
              rows={4}
              className="mt-1 block w-full rounded-md border border-divider bg-paper px-3 py-2 text-sm text-charcoal"
              placeholder="Describe the task, tools/equipment, hazards, and control measures."
            />
          </div>
        </div>

        {/* Step 3 */}
        <div className="space-y-4">
          <h3 className="text-sm font-semibold text-charcoal">
            Step 3 — PPE &amp; Safety Compliance
          </h3>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
            {ppeItems.length === 0 && (
              <p className="text-sm text-slate">
                {selectedType
                  ? "No PPE items configured for this permit type."
                  : "Select a permit type in step 1 to load PPE items."}
              </p>
            )}
            {ppeItems.map((item: PpeItemTemplate) => {
              const checked = confirmedPpe.has(item.id);
              return (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => togglePpe(item.id)}
                  className={`flex items-center gap-2 rounded-md border px-3 py-2 text-left text-sm transition ${
                    checked
                      ? "border-emerald bg-emerald/10 text-emerald"
                      : "border-divider bg-paper text-slate hover:border-emerald/30"
                  }`}
                >
                  <span
                    className={`flex h-4 w-4 shrink-0 items-center justify-center rounded border ${
                      checked ? "border-emerald bg-emerald text-white" : "border-divider"
                    }`}
                  >
                    {checked ? "✓" : ""}
                  </span>
                  <span className="text-xs font-semibold">{item.name}</span>
                </button>
              );
            })}
          </div>
          <div className="rounded-md border border-amber-flame/30 bg-amber-flame/10 p-4 text-sm text-charcoal">
            <p className="text-xs font-semibold uppercase tracking-wider text-amber-flame">
              Declaration
            </p>
            <p className="mt-1 text-xs">
              I confirm the above information is accurate, the work area has been inspected, and all
              workers are briefed on hazards. This permit is subject to revocation by the HSE
              Officer at any time if unsafe conditions arise.
            </p>
            <label className="mt-3 inline-flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={declarationAccepted}
                onChange={(e) => setDeclarationAccepted(e.target.checked)}
              />
              I accept the declaration.
            </label>
          </div>
        </div>

        {/* Step 4 */}
        <div className="space-y-3">
          <h3 className="text-sm font-semibold text-charcoal">Step 4 — Review &amp; Submit</h3>
          <ReviewRow label="Project">
            {selectedProject
              ? selectedProject.code
                ? `${selectedProject.code} — ${selectedProject.name}`
                : selectedProject.name
              : projectId || "—"}
          </ReviewRow>
          <ReviewRow label="Permit Type">
            {selectedType ? (
              <PermitTypeBadge
                code={selectedType.code}
                name={selectedType.name}
                colorHex={selectedType.colorHex}
              />
            ) : (
              "—"
            )}
          </ReviewRow>
          <ReviewRow label="Risk">
            <RiskBadge level={riskLevel} />
          </ReviewRow>
          <ReviewRow label="Workers">
            {workers.filter((w) => w.fullName.trim()).map((w) => `${w.fullName} (${w.roleOnPermit})`).join(", ") || "—"}
          </ReviewRow>
          <ReviewRow label="Location / Chainage">
            {locationZone}
            {chainageMarker ? ` · ${chainageMarker}` : ""}
          </ReviewRow>
          <ReviewRow label="Window">
            {new Date(startAt).toLocaleString()} → {new Date(endAt).toLocaleString()} · {shift}
          </ReviewRow>
          <ReviewRow label="Task">{taskDescription || "—"}</ReviewRow>
          <ReviewRow label="PPE Confirmed">
            {confirmedPpe.size === 0
              ? "None"
              : ppeItems
                  .filter((i) => confirmedPpe.has(i.id))
                  .map((i) => i.name)
                  .join(", ")}
          </ReviewRow>
          <ReviewRow label="Declaration">
            {declarationAccepted ? "Accepted" : "Pending"}
          </ReviewRow>
          <p className="text-xs text-slate">
            On submission this permit enters PENDING_SITE_ENGINEER and the approval timeline is
            locked. Edits afterwards require a permit amendment.
          </p>
        </div>
      </WizardShell>

      {error && (
        <div className="rounded-md border border-burgundy/40 bg-burgundy/10 px-4 py-2 text-sm text-burgundy">
          {error}
        </div>
      )}
    </div>
  );
}

function FieldInput({
  label,
  value,
  onChange,
  placeholder,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
}) {
  return (
    <div>
      <label className="text-[11px] font-semibold uppercase tracking-wider text-slate">
        {label}
      </label>
      <input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className="mt-1 block w-full rounded-md border border-divider bg-paper px-3 py-1.5 text-sm text-charcoal"
      />
    </div>
  );
}

function DateTimeInput({
  label,
  value,
  onChange,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
}) {
  return (
    <div>
      <label className="text-xs font-semibold uppercase tracking-wider text-slate">{label}</label>
      <input
        type="datetime-local"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="mt-1 block w-full rounded-md border border-divider bg-paper px-3 py-2 text-sm text-charcoal"
      />
    </div>
  );
}

function ReviewRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-3 items-baseline gap-2 border-b border-hairline py-2 text-sm last:border-0">
      <span className="text-xs font-semibold uppercase tracking-wider text-slate">{label}</span>
      <span className="col-span-2 text-charcoal">{children}</span>
    </div>
  );
}
