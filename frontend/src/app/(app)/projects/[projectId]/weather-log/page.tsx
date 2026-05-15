"use client";

import { useEffect, useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  dailyWeatherApi,
  type DailyWeatherResponse,
  type CreateDailyWeatherRequest,
} from "@/lib/api/dailyWeatherApi";
import { projectApi } from "@/lib/api/projectApi";
import { TabTip } from "@/components/common/TabTip";
import { getErrorMessage } from "@/lib/utils/error";
import { VirtualDataTable } from "@/components/common/VirtualDataTable";
import type { ColumnDef } from "@tanstack/react-table";
import { useAuthStore } from "@/lib/state/store";

interface WeatherForm {
  logDate: string;
  tempMaxC: string;
  tempMinC: string;
  rainfallMm: string;
  windKmh: string;
  weatherCondition: string;
  weatherConditionOther: string;
  workingHours: string;
  remarks: string;
}

const CONDITION_PRESETS = ["Clear", "Cloudy", "Rain", "Hot", "Sunny", "Overcast"];

const todayIso = () => new Date().toISOString().split("T")[0];

const initialFormState = (): WeatherForm => ({
  logDate: todayIso(),
  tempMaxC: "",
  tempMinC: "",
  rainfallMm: "",
  windKmh: "",
  weatherCondition: "",
  weatherConditionOther: "",
  workingHours: "",
  remarks: "",
});

const toNumberOrNull = (value: string): number | null => {
  if (value === "") return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
};

export default function WeatherLogPage() {
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canWrite = hasPermission("DPR.UPDATE");
  const params = useParams();
  const projectId = params.projectId as string;

  const queryClient = useQueryClient();

  const { data: projectData } = useQuery({
    queryKey: ["project", projectId],
    queryFn: () => projectApi.getProject(projectId),
    enabled: !!projectId,
  });
  const project = projectData?.data;

  const [fromDate, setFromDate] = useState("");
  const [toDate, setToDate] = useState("");
  const [appliedFrom, setAppliedFrom] = useState("");
  const [appliedTo, setAppliedTo] = useState("");

  useEffect(() => {
    if (!project) return;
    if (appliedFrom === "" && project.plannedStartDate) {
      setFromDate(project.plannedStartDate);
      setAppliedFrom(project.plannedStartDate);
    }
    if (appliedTo === "" && project.plannedFinishDate) {
      setToDate(project.plannedFinishDate);
      setAppliedTo(project.plannedFinishDate);
    }
  }, [project, appliedFrom, appliedTo]);

  const [showForm, setShowForm] = useState(false);
  const [formData, setFormData] = useState<WeatherForm>(initialFormState());
  const [error, setError] = useState<string | null>(null);

  const { data, isLoading, isError, error: queryError } = useQuery({
    queryKey: ["weather", projectId, appliedFrom, appliedTo],
    queryFn: () =>
      dailyWeatherApi.list(projectId, { from: appliedFrom, to: appliedTo }),
    enabled: !!projectId && !!appliedFrom && !!appliedTo,
  });

  const entries: DailyWeatherResponse[] = useMemo(() => data?.data ?? [], [data]);

  const handleApply = () => {
    setAppliedFrom(fromDate);
    setAppliedTo(toDate);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      const condition =
        formData.weatherCondition === "Other"
          ? formData.weatherConditionOther.trim() || null
          : formData.weatherCondition || null;

      const payload: CreateDailyWeatherRequest = {
        logDate: formData.logDate,
        tempMaxC: toNumberOrNull(formData.tempMaxC),
        tempMinC: toNumberOrNull(formData.tempMinC),
        rainfallMm: toNumberOrNull(formData.rainfallMm),
        windKmh: toNumberOrNull(formData.windKmh),
        weatherCondition: condition,
        workingHours: toNumberOrNull(formData.workingHours),
        remarks: formData.remarks.trim() || null,
      };

      await dailyWeatherApi.upsert(projectId, payload);
      setFormData(initialFormState());
      setShowForm(false);
      queryClient.invalidateQueries({ queryKey: ["weather", projectId] });
    } catch (err: unknown) {
      setError(getErrorMessage(err, "Failed to save weather entry"));
    }
  };

  const handleDelete = async (id: string) => {
    if (!window.confirm("Delete this weather entry?")) return;
    try {
      await dailyWeatherApi.delete(projectId, id);
      queryClient.invalidateQueries({ queryKey: ["weather", projectId] });
    } catch (err: unknown) {
      setError(getErrorMessage(err, "Failed to delete weather entry"));
    }
  };

  const columns = useMemo<ColumnDef<DailyWeatherResponse>[]>(() => [
    { accessorKey: "logDate", header: "Date" },
    {
      accessorKey: "tempMaxC",
      header: "Temp Max (°C)",
      cell: ({ row }) => row.original.tempMaxC ?? "—",
    },
    {
      accessorKey: "tempMinC",
      header: "Temp Min (°C)",
      cell: ({ row }) => row.original.tempMinC ?? "—",
    },
    {
      accessorKey: "rainfallMm",
      header: "Rainfall (mm)",
      cell: ({ row }) => row.original.rainfallMm ?? "—",
    },
    {
      accessorKey: "windKmh",
      header: "Wind (km/h)",
      cell: ({ row }) => row.original.windKmh ?? "—",
    },
    {
      accessorKey: "weatherCondition",
      header: "Condition",
      cell: ({ row }) => row.original.weatherCondition ?? "—",
    },
    {
      accessorKey: "workingHours",
      header: "Working Hrs",
      cell: ({ row }) => row.original.workingHours ?? "—",
    },
    {
      accessorKey: "remarks",
      header: "Remarks",
      cell: ({ row }) => row.original.remarks ?? "—",
    },
    {
      id: "actions",
      header: "Actions",
      cell: ({ row }) =>
        canWrite ? (
          <button
            onClick={() => handleDelete(row.original.id)}
            className="text-danger hover:underline text-sm"
          >
            Delete
          </button>
        ) : (
          <span className="text-text-muted text-sm">—</span>
        ),
    },
  ], [handleDelete, canWrite]);

  return (
    <div className="p-6">
      <TabTip
        title="Daily Weather Log"
        description="Temperature, rainfall, wind and working hours — captured per day."
      />

      <div className="mb-8">
        <h1 className="text-3xl font-bold mb-4 text-text-primary">Daily Weather Log</h1>

        {/* Filters */}
        <div className="flex flex-wrap items-end gap-3 mb-6">
          <div>
            <label className="block text-sm font-medium mb-1 text-text-secondary">From</label>
            <input
              type="date"
              value={fromDate}
              onChange={(e) => setFromDate(e.target.value)}
              className="px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
            />
          </div>
          <div>
            <label className="block text-sm font-medium mb-1 text-text-secondary">To</label>
            <input
              type="date"
              value={toDate}
              onChange={(e) => setToDate(e.target.value)}
              className="px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
            />
          </div>
          <button
            onClick={handleApply}
            className="px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover"
          >
            Apply
          </button>
          {canWrite && (
            <button
              onClick={() => {
                setShowForm(!showForm);
                setError(null);
              }}
              className="px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover ml-auto"
            >
              {showForm ? "Cancel" : "Add / Update Entry"}
            </button>
          )}
        </div>

        {error && <div className="text-danger mb-4">{error}</div>}
        {isError && (
          <div className="text-danger mb-4">
            {getErrorMessage(queryError, "Failed to load weather entries")}
          </div>
        )}

        {showForm && (
          <form
            onSubmit={handleSubmit}
            className="bg-surface/50 p-4 rounded-lg border border-border mb-6 shadow-xl"
          >
            <p className="text-sm text-text-secondary mb-3">
              Entries are keyed by date — saving an existing date updates that row.
            </p>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Date
                </label>
                <input
                  type="date"
                  value={formData.logDate}
                  onChange={(e) => setFormData({ ...formData, logDate: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  required
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Condition
                </label>
                <select
                  value={formData.weatherCondition}
                  onChange={(e) =>
                    setFormData({ ...formData, weatherCondition: e.target.value })
                  }
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                >
                  <option value="">—</option>
                  {CONDITION_PRESETS.map((c) => (
                    <option key={c} value={c}>
                      {c}
                    </option>
                  ))}
                  <option value="Other">Other…</option>
                </select>
                {formData.weatherCondition === "Other" && (
                  <input
                    type="text"
                    value={formData.weatherConditionOther}
                    onChange={(e) =>
                      setFormData({ ...formData, weatherConditionOther: e.target.value })
                    }
                    placeholder="Describe condition"
                    className="mt-2 w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  />
                )}
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Temp Max (°C)
                </label>
                <input
                  type="number"
                  step="0.1"
                  value={formData.tempMaxC}
                  onChange={(e) => setFormData({ ...formData, tempMaxC: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Temp Min (°C)
                </label>
                <input
                  type="number"
                  step="0.1"
                  value={formData.tempMinC}
                  onChange={(e) => setFormData({ ...formData, tempMinC: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Rainfall (mm)
                </label>
                <input
                  type="number"
                  step="0.1"
                  value={formData.rainfallMm}
                  onChange={(e) => setFormData({ ...formData, rainfallMm: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Wind (km/h)
                </label>
                <input
                  type="number"
                  step="0.1"
                  value={formData.windKmh}
                  onChange={(e) => setFormData({ ...formData, windKmh: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Working Hours
                </label>
                <input
                  type="number"
                  step="0.1"
                  value={formData.workingHours}
                  onChange={(e) =>
                    setFormData({ ...formData, workingHours: e.target.value })
                  }
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div className="md:col-span-2">
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Remarks
                </label>
                <textarea
                  value={formData.remarks}
                  onChange={(e) => setFormData({ ...formData, remarks: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  rows={3}
                />
              </div>
            </div>
            <div className="flex gap-2 mt-4">
              <button
                type="submit"
                className="px-4 py-2 bg-green-600 text-text-primary rounded-lg hover:bg-green-600"
              >
                Save Entry
              </button>
              <button
                type="button"
                onClick={() => setShowForm(false)}
                className="px-4 py-2 bg-surface-active/50 text-text-secondary rounded-lg hover:bg-border"
              >
                Cancel
              </button>
            </div>
          </form>
        )}

        <VirtualDataTable
          columns={columns}
          data={entries}
          sortable
          resizable
          isLoading={isLoading}
          emptyMessage="No weather entries in this date range."
        />
      </div>
    </div>
  );
}
