"use client";

import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Trash2 } from "lucide-react";
import Link from "next/link";
import toast from "react-hot-toast";
import { resourceApi, type ResourceResponse } from "@/lib/api/resourceApi";
import { manpowerRateMasterApi } from "@/lib/api/manpowerRateMasterApi";
import { equipmentRateMasterApi } from "@/lib/api/equipmentRateMasterApi";
import { materialRateMasterApi } from "@/lib/api/materialRateMasterApi";
import { VirtualDataTable } from "@/components/common/VirtualDataTable";
import type { ColumnDef } from "@tanstack/react-table";
import { PageHeader } from "@/components/common/PageHeader";
import { EmptyState } from "@/components/common/EmptyState";
import { StatusBadge } from "@/components/common/StatusBadge";
import { TabTip } from "@/components/common/TabTip";
import { notificationHelpers } from "@/lib/notificationHelpers";
import { displayResourceTypeName } from "@/lib/utils/resourceTypeLabel";

type TypeTab = "ALL" | "MANPOWER" | "EQUIPMENT" | "MATERIAL";

const TYPE_TABS: { key: TypeTab; label: string }[] = [
  { key: "ALL", label: "All" },
  { key: "MANPOWER", label: "Manpower" },
  { key: "EQUIPMENT", label: "Equipment" },
  { key: "MATERIAL", label: "Material" },
];

// Tab key already matches the backend resource_types.code now that LABOR was
// renamed to MANPOWER. Kept as a function for symmetry / future remapping.
const tabKeyToTypeCode = (key: TypeTab): string => key;

export default function ResourcesPage() {
  const queryClient = useQueryClient();
  const [typeTab, setTypeTab] = useState<TypeTab>("ALL");

  const { data: resourcesData, isLoading, error } = useQuery({
    queryKey: ["resources"],
    queryFn: () => resourceApi.listResources(),
  });

  const { data: manpowerRatesData } = useQuery({
    queryKey: ["manpower-rate-master"],
    queryFn: () => manpowerRateMasterApi.list(),
  });
  const { data: equipmentRatesData } = useQuery({
    queryKey: ["equipment-rate-master"],
    queryFn: () => equipmentRateMasterApi.list(),
  });
  const { data: materialRatesData } = useQuery({
    queryKey: ["material-rate-master"],
    queryFn: () => materialRateMasterApi.list(),
  });

  /** Lookup map: rate-master row id → short label for the grid column. */
  const rateMasterLabels = useMemo(() => {
    const map = new Map<string, string>();
    for (const r of manpowerRatesData?.data ?? []) {
      map.set(r.id, `${r.roleName ?? "?"} / Grade ${r.gradeCode ?? "?"} — ${r.unit} @ ${r.rate}`);
    }
    for (const r of equipmentRatesData?.data ?? []) {
      map.set(r.id, `${r.equipmentName} / ${r.make} ${r.model} — ${r.unit} @ ${r.rate}`);
    }
    for (const r of materialRatesData?.data ?? []) {
      map.set(r.id, `${r.categoryName ?? "?"} / ${r.specGrade} — ${r.unit} @ ${r.rate}`);
    }
    return map;
  }, [manpowerRatesData, equipmentRatesData, materialRatesData]);

  const deleteMutation = useMutation({
    mutationFn: (resourceId: string) => resourceApi.deleteResource(resourceId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["resources"] });
      toast.success("Resource deleted successfully");
    },
    onError: (err) => notificationHelpers.handleApiError(err, "Failed to delete resource"),
  });

  const deleteAllMutation = useMutation({
    mutationFn: () => resourceApi.deleteAllResources(),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["resources"] });
      toast.success("All resources deleted successfully");
    },
    onError: (err) => notificationHelpers.handleApiError(err, "Failed to delete all resources"),
  });

  const allResources = useMemo<ResourceResponse[]>(() => {
    const rawData = resourcesData?.data;
    return Array.isArray(rawData)
      ? rawData
      : ((rawData as unknown as { content?: ResourceResponse[] } | null)?.content ?? []);
  }, [resourcesData]);

  const resources = useMemo(() => {
    if (typeTab === "ALL") return allResources;
    const code = tabKeyToTypeCode(typeTab);
    return allResources.filter((r) => r.resourceTypeCode === code);
  }, [allResources, typeTab]);

  // Columns vary by tab. The list endpoint returns slim fields only — detail blocks come on
  // /v1/resources/{id}, not the list — so we render only what's on ResourceResponse.
  const columns = useMemo<ColumnDef<ResourceResponse>[]>(() => {
    const baseCols: ColumnDef<ResourceResponse>[] = [
      { accessorKey: "code", header: "Code", enableSorting: true },
      { accessorKey: "name", header: "Name", enableSorting: true },
    ];

    const typeCol: ColumnDef<ResourceResponse> = {
      accessorKey: "resourceTypeName",
      header: "Type",
      enableSorting: true,
      cell: (info) => {
        const row = info.row.original;
        return (
          <span className="text-sm font-medium">
            {row.resourceTypeName
              ? displayResourceTypeName(row.resourceTypeName)
              : row.resourceTypeCode ?? "—"}
          </span>
        );
      },
    };

    const roleCol: ColumnDef<ResourceResponse> = {
      accessorKey: "roleName",
      header: "Role",
      enableSorting: true,
      cell: (info) => {
        const row = info.row.original;
        return row.roleName ?? "—";
      },
    };

    const rateMasterCol: ColumnDef<ResourceResponse> = {
      accessorKey: "rateMasterId",
      header: "Rate Master",
      enableSorting: true,
      cell: (info) => {
        const id = info.row.original.rateMasterId;
        if (!id) return <span className="text-text-muted">—</span>;
        const label = rateMasterLabels.get(id);
        return <span className="text-sm text-text-secondary">{label ?? "linked"}</span>;
      },
    };

    const unitCol: ColumnDef<ResourceResponse> = {
      accessorKey: "unit",
      header: "Unit",
      enableSorting: true,
      cell: (info) => {
        const row = info.row.original;
        return row.unit ?? "—";
      },
    };

    const rateCol: ColumnDef<ResourceResponse> = {
      accessorKey: "costPerUnit",
      header: "Rate",
      enableSorting: true,
      cell: (info) => {
        const v = info.row.original.costPerUnit;
        // Global resource master — rates are currency-neutral (interpreted in each
        // project's own currency), so no currency symbol here.
        return v == null
          ? "—"
          : Number(v).toLocaleString(undefined, { maximumFractionDigits: 2 });
      },
    };

    const statusCol: ColumnDef<ResourceResponse> = {
      accessorKey: "status",
      header: "Status",
      cell: (info) => <StatusBadge status={String(info.getValue())} />,
    };

    const actionsCol: ColumnDef<ResourceResponse> = {
      id: "actions",
      header: "Actions",
      cell: (info) => {
        const row = info.row.original;
        return (
          <div className="flex items-center gap-2">
            <Link
              href={`/resources/${row.id}`}
              className="text-accent hover:underline text-sm"
              onClick={(e) => e.stopPropagation()}
            >
              View
            </Link>
            <button
              onClick={(e) => {
                e.stopPropagation();
                if (window.confirm("Delete this resource?")) {
                  deleteMutation.mutate(String(row.id));
                }
              }}
              disabled={deleteMutation.isPending}
              className="text-text-secondary hover:text-danger disabled:text-text-muted"
              title="Delete resource"
            >
              <Trash2 size={16} />
            </button>
          </div>
        );
      },
    };

    // Phase 8: every tab now shows Unit + Rate; Availability column dropped (low-value clutter).
    return [...baseCols, typeCol, roleCol, rateMasterCol, unitCol, rateCol, statusCol, actionsCol];
  }, [typeTab, deleteMutation, rateMasterLabels]);

  return (
    <div className="h-full flex flex-col">
      <PageHeader
        title="Resources"
        description="Manpower, equipment and material resources used across projects"
        actions={
          <div className="flex items-center gap-2">
            <button
              onClick={() => {
                if (
                  window.confirm(
                    "Are you sure you want to delete ALL resources? This action cannot be undone."
                  )
                ) {
                  deleteAllMutation.mutate();
                }
              }}
              disabled={deleteAllMutation.isPending || allResources.length === 0}
              className="inline-flex items-center gap-2 rounded-md bg-danger px-4 py-2 text-sm font-medium text-white hover:bg-danger/90 disabled:opacity-50"
            >
              <Trash2 size={16} />
              {deleteAllMutation.isPending ? "Deleting..." : "Delete All"}
            </button>
            <Link
              href="/resources/new"
              className="inline-flex items-center gap-2 rounded-md bg-accent px-4 py-2 text-sm font-medium text-text-primary hover:bg-accent-hover"
            >
              <Plus size={16} />
              New Resource
            </Link>
          </div>
        }
      />

      <TabTip
        title="Global Resource Pool"
        description="Define every resource (people, equipment, materials) that can be assigned to projects. Click View on a row to open its detail tabs."
      />

      {/* Type tabs */}
      <div className="mt-4 mb-4 flex flex-wrap gap-2">
        {TYPE_TABS.map((t) => (
          <button
            key={t.key}
            onClick={() => setTypeTab(t.key)}
            className={`rounded-md px-4 py-2 text-sm font-medium transition-colors ${
              typeTab === t.key
                ? "bg-accent text-text-primary"
                : "border border-border bg-surface/50 text-text-secondary hover:bg-surface-hover/50"
            }`}
          >
            {t.label}
            <span className="ml-2 inline-flex items-center justify-center rounded bg-surface-hover/60 px-1.5 py-0.5 text-xs">
              {t.key === "ALL"
                ? allResources.length
                : allResources.filter((r) => r.resourceTypeCode === tabKeyToTypeCode(t.key)).length}
            </span>
          </button>
        ))}
      </div>

      {isLoading && (
        <div className="py-12 text-center text-text-muted">Loading resources...</div>
      )}

      {error && (
        <div className="rounded-md bg-danger/10 p-4 text-sm text-danger">
          Failed to load resources. Is the backend running?
        </div>
      )}

      {!isLoading && resources.length === 0 && (
        <EmptyState
          title={
            typeTab === "ALL"
              ? "No resources yet"
              : `No ${typeTab.toLowerCase()} resources`
          }
          description="Create your first resource to get started with resource management."
        />
      )}

      {resources.length > 0 && (
        <VirtualDataTable
          columns={columns}
          data={resources}
          searchable
          sortable
          resizable
          fullHeight
          className="flex-1 min-h-0"
        />
      )}
    </div>
  );
}
