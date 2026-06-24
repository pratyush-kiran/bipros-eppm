"use client";

import { useState, useEffect } from "react";
import { useParams, useRouter } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Plus, Trash2, Save, Clock } from "lucide-react";
import { PageHeader } from "@/components/common/PageHeader";
import { SimpleTable } from "@/components/common/SimpleTable";
import type { ColumnDef } from "@tanstack/react-table";
import {
  calendarApi,
  type CalendarWorkWeekRequest,
  type CalendarExceptionRequest,
  type CalendarWorkWeekResponse,
  type CalendarExceptionResponse,
} from "@/lib/api/calendarApi";

const DAYS_OF_WEEK = [
  "MONDAY",
  "TUESDAY",
  "WEDNESDAY",
  "THURSDAY",
  "FRIDAY",
  "SATURDAY",
  "SUNDAY",
] as const;

const DAY_LABELS: Record<string, string> = {
  MONDAY: "Monday",
  TUESDAY: "Tuesday",
  WEDNESDAY: "Wednesday",
  THURSDAY: "Thursday",
  FRIDAY: "Friday",
  SATURDAY: "Saturday",
  SUNDAY: "Sunday",
};

type TabType = "details" | "workweek" | "exceptions";

interface WorkWeekRow {
  dayOfWeek: string;
  dayType: "WORKING" | "NON_WORKING";
  startTime1: string;
  endTime1: string;
  startTime2: string;
  endTime2: string;
}

function defaultWorkWeek(): WorkWeekRow[] {
  return DAYS_OF_WEEK.map((day) => ({
    dayOfWeek: day,
    dayType: ["SATURDAY", "SUNDAY"].includes(day) ? "NON_WORKING" : "WORKING",
    startTime1: "08:00",
    endTime1: "12:00",
    startTime2: "13:00",
    endTime2: "17:00",
  }));
}

function toWorkWeekRows(workWeeks: CalendarWorkWeekResponse[]): WorkWeekRow[] {
  const map = new Map(workWeeks.map((ww) => [ww.dayOfWeek, ww]));
  return DAYS_OF_WEEK.map((day) => {
    const ww = map.get(day);
    if (ww) {
      return {
        dayOfWeek: day,
        dayType: ww.dayType === "WORKING" ? "WORKING" : "NON_WORKING",
        startTime1: ww.startTime1 ?? "08:00",
        endTime1: ww.endTime1 ?? "12:00",
        startTime2: ww.startTime2 ?? "13:00",
        endTime2: ww.endTime2 ?? "17:00",
      };
    }
    return {
      dayOfWeek: day,
      dayType: ["SATURDAY", "SUNDAY"].includes(day)
        ? ("NON_WORKING" as const)
        : ("WORKING" as const),
      startTime1: "08:00",
      endTime1: "12:00",
      startTime2: "13:00",
      endTime2: "17:00",
    };
  });
}

export default function CalendarDetailPage() {
  const params = useParams();
  const router = useRouter();
  const queryClient = useQueryClient();
  const calendarId = params.id as string;

  const [activeTab, setActiveTab] = useState<TabType>("details");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [calendarType, setCalendarType] = useState<
    "GLOBAL" | "PROJECT" | "RESOURCE"
  >("GLOBAL");
  const [hoursPerDay, setHoursPerDay] = useState(8);
  const [daysPerWeek, setDaysPerWeek] = useState(5);
  const [workWeek, setWorkWeek] = useState<WorkWeekRow[]>(defaultWorkWeek());
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");

  // Exception form
  const [excDate, setExcDate] = useState("");
  const [excName, setExcName] = useState("");
  const [excDayType, setExcDayType] = useState<
    "NON_WORKING" | "WORKING" | "EXCEPTION_WORKING" | "EXCEPTION_NON_WORKING"
  >("NON_WORKING");
  const [excStart1, setExcStart1] = useState("08:00");
  const [excEnd1, setExcEnd1] = useState("12:00");
  const [excStart2, setExcStart2] = useState("13:00");
  const [excEnd2, setExcEnd2] = useState("17:00");

  const { data: calendarData, isLoading } = useQuery({
    queryKey: ["calendar", calendarId],
    queryFn: () => calendarApi.getCalendar(calendarId),
  });

  const calendar = calendarData?.data;

  useEffect(() => {
    if (calendar) {
      setName(calendar.calendar.name);
      setDescription(calendar.calendar.description ?? "");
      setCalendarType(calendar.calendar.calendarType);
      setHoursPerDay(calendar.calendar.standardWorkHoursPerDay);
      setDaysPerWeek(calendar.calendar.standardWorkDaysPerWeek);
      if (calendar.workWeeks.length > 0) {
        setWorkWeek(toWorkWeekRows(calendar.workWeeks));
      }
    }
  }, [calendar]);

  // Exceptions query — show current year range
  const now = new Date();
  const yearStart = `${now.getFullYear()}-01-01`;
  const yearEnd = `${now.getFullYear()}-12-31`;

  const { data: exceptionsData, refetch: refetchExceptions } = useQuery({
    queryKey: ["calendar-exceptions", calendarId],
    queryFn: () => calendarApi.getExceptions(calendarId, yearStart, yearEnd),
    enabled: activeTab === "exceptions",
  });

  const exceptions: CalendarExceptionResponse[] = exceptionsData?.data ?? [];

  const handleSaveDetails = async () => {
    setSaving(true);
    setError("");
    setSuccess("");
    try {
      await calendarApi.updateCalendar(calendarId, {
        name,
        description: description || undefined,
        calendarType,
        standardWorkHoursPerDay: hoursPerDay,
        standardWorkDaysPerWeek: daysPerWeek,
      });
      queryClient.invalidateQueries({ queryKey: ["calendar", calendarId] });
      // Also refresh the list cache — name/type/hours/days are shown there, and
      // the global 5-min staleTime would otherwise keep the old row until refresh.
      queryClient.invalidateQueries({ queryKey: ["calendars"] });
      setSuccess("Calendar updated successfully");
    } catch (err: unknown) {
      const msg =
        err instanceof Error ? err.message : "Failed to update calendar";
      setError(msg);
    } finally {
      setSaving(false);
    }
  };

  const handleSaveWorkWeek = async () => {
    setSaving(true);
    setError("");
    setSuccess("");
    try {
      const data: CalendarWorkWeekRequest[] = workWeek.map((row) => ({
        dayOfWeek: row.dayOfWeek,
        dayType: row.dayType,
        ...(row.dayType === "WORKING"
          ? {
              startTime1: row.startTime1,
              endTime1: row.endTime1,
              startTime2: row.startTime2,
              endTime2: row.endTime2,
            }
          : {}),
      }));
      await calendarApi.setWorkWeek(calendarId, data);
      queryClient.invalidateQueries({ queryKey: ["calendar", calendarId] });
      setSuccess("Work week saved successfully");
    } catch (err: unknown) {
      const msg =
        err instanceof Error ? err.message : "Failed to save work week";
      setError(msg);
    } finally {
      setSaving(false);
    }
  };

  const handleAddException = async () => {
    if (!excDate) {
      setError("Exception date is required");
      return;
    }
    setSaving(true);
    setError("");
    setSuccess("");
    try {
      const data: CalendarExceptionRequest = {
        exceptionDate: excDate,
        dayType: excDayType,
        name: excName || undefined,
        ...(excDayType === "WORKING" || excDayType === "EXCEPTION_WORKING"
          ? {
              startTime1: excStart1,
              endTime1: excEnd1,
              startTime2: excStart2,
              endTime2: excEnd2,
            }
          : {}),
      };
      await calendarApi.addException(calendarId, data);
      setExcDate("");
      setExcName("");
      setExcDayType("NON_WORKING");
      refetchExceptions();
      setSuccess("Exception added");
    } catch (err: unknown) {
      const msg =
        err instanceof Error ? err.message : "Failed to add exception";
      setError(msg);
    } finally {
      setSaving(false);
    }
  };

  const handleRemoveException = async (exceptionId: string) => {
    try {
      await calendarApi.removeException(calendarId, exceptionId);
      refetchExceptions();
    } catch (err: unknown) {
      const msg =
        err instanceof Error ? err.message : "Failed to remove exception";
      setError(msg);
    }
  };

  const updateWorkWeekRow = (
    index: number,
    field: keyof WorkWeekRow,
    value: string
  ) => {
    setWorkWeek((prev) =>
      prev.map((row, i) => (i === index ? { ...row, [field]: value } : row))
    );
  };

  const tabs: { key: TabType; label: string }[] = [
    { key: "details", label: "Details" },
    { key: "workweek", label: "Work Week" },
    { key: "exceptions", label: "Exceptions" },
  ];

  const inputClass =
    "mt-1 block w-full rounded-md border border-border bg-surface-hover/50 px-3 py-2 text-text-primary placeholder-gray-500 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent text-sm";

  if (isLoading) {
    return (
      <div className="py-12 text-center text-text-muted">
        Loading calendar...
      </div>
    );
  }

  return (
    <div>
      <PageHeader
        title={name || "Calendar Detail"}
        description="Configure calendar properties, work week, and exceptions"
        actions={
          <button
            onClick={() => router.push("/admin/calendars")}
            className="inline-flex items-center gap-2 rounded-md bg-surface-active/50 px-4 py-2 text-sm font-medium text-text-secondary hover:bg-surface-active"
          >
            <ArrowLeft size={16} />
            Back
          </button>
        }
      />

      {error && (
        <div className="mb-4 rounded-md bg-danger/10 p-3 text-sm text-danger">
          {error}
        </div>
      )}
      {success && (
        <div className="mb-4 rounded-md bg-success/10 p-3 text-sm text-success">
          {success}
        </div>
      )}

      {/* Tabs */}
      <div className="mb-6 flex gap-1 rounded-lg bg-surface-hover/50 p-1">
        {tabs.map((tab) => (
          <button
            key={tab.key}
            onClick={() => {
              setActiveTab(tab.key);
              setError("");
              setSuccess("");
            }}
            className={`rounded-md px-4 py-2 text-sm font-medium transition-colors ${
              activeTab === tab.key
                ? "bg-accent text-accent-foreground"
                : "text-text-secondary hover:text-text-primary"
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Details Tab */}
      {activeTab === "details" && (
        <div className="rounded-lg border border-border bg-surface/50 p-6">
          <div className="grid grid-cols-2 gap-6">
            <div>
              <label className="block text-sm font-medium text-text-secondary">
                Name *
              </label>
              <input
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                className={inputClass}
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-text-secondary">
                Type
              </label>
              <select
                value={calendarType}
                onChange={(e) =>
                  setCalendarType(
                    e.target.value as "GLOBAL" | "PROJECT" | "RESOURCE"
                  )
                }
                className={inputClass}
              >
                <option value="GLOBAL">Global</option>
                <option value="PROJECT">Project</option>
                <option value="RESOURCE">Resource</option>
              </select>
            </div>
            <div className="col-span-2">
              <label className="block text-sm font-medium text-text-secondary">
                Description
              </label>
              <input
                type="text"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                className={inputClass}
                placeholder="Optional description"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-text-secondary">
                Hours / Day
              </label>
              <input
                type="number"
                value={hoursPerDay}
                onChange={(e) => setHoursPerDay(parseInt(e.target.value, 10))}
                min={1}
                max={24}
                className={inputClass}
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-text-secondary">
                Days / Week
              </label>
              <input
                type="number"
                value={daysPerWeek}
                onChange={(e) => setDaysPerWeek(parseInt(e.target.value, 10))}
                min={1}
                max={7}
                className={inputClass}
              />
            </div>
          </div>
          <div className="mt-6 flex justify-end">
            <button
              onClick={handleSaveDetails}
              disabled={saving}
              className="inline-flex items-center gap-2 rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:bg-border"
            >
              <Save size={16} />
              {saving ? "Saving..." : "Save Details"}
            </button>
          </div>
        </div>
      )}

      {/* Work Week Tab */}
      {activeTab === "workweek" && (
        <div className="rounded-lg border border-border bg-surface/50 p-6">
          <div className="mb-4 flex items-center gap-2 text-sm text-text-secondary">
            <Clock size={16} />
            Configure working hours for each day of the week. Non-working days
            ignore time ranges.
          </div>
          <SimpleTable
            columns={[
              {
                accessorKey: "dayOfWeek",
                header: "Day",
                cell: ({ row }) => <span className="font-medium text-text-primary">{DAY_LABELS[row.original.dayOfWeek]}</span>,
              },
              {
                accessorKey: "dayType",
                header: "Type",
                cell: ({ row }) => {
                  const idx = workWeek.findIndex((w) => w.dayOfWeek === row.original.dayOfWeek);
                  return (
                    <select
                      value={row.original.dayType}
                      onChange={(e) => updateWorkWeekRow(idx, "dayType", e.target.value)}
                      className="rounded border border-border bg-surface-hover px-2 py-1 text-sm text-text-primary"
                    >
                      <option value="WORKING">Working</option>
                      <option value="NON_WORKING">Non-Working</option>
                    </select>
                  );
                },
              },
              {
                accessorKey: "startTime1",
                header: "Morning Start",
                cell: ({ row }) => {
                  const idx = workWeek.findIndex((w) => w.dayOfWeek === row.original.dayOfWeek);
                  return (
                    <input
                      type="time"
                      value={row.original.startTime1}
                      onChange={(e) => updateWorkWeekRow(idx, "startTime1", e.target.value)}
                      disabled={row.original.dayType === "NON_WORKING"}
                      className="rounded border border-border bg-surface-hover px-2 py-1 text-sm text-text-primary disabled:opacity-40"
                    />
                  );
                },
              },
              {
                accessorKey: "endTime1",
                header: "Morning End",
                cell: ({ row }) => {
                  const idx = workWeek.findIndex((w) => w.dayOfWeek === row.original.dayOfWeek);
                  return (
                    <input
                      type="time"
                      value={row.original.endTime1}
                      onChange={(e) => updateWorkWeekRow(idx, "endTime1", e.target.value)}
                      disabled={row.original.dayType === "NON_WORKING"}
                      className="rounded border border-border bg-surface-hover px-2 py-1 text-sm text-text-primary disabled:opacity-40"
                    />
                  );
                },
              },
              {
                accessorKey: "startTime2",
                header: "Afternoon Start",
                cell: ({ row }) => {
                  const idx = workWeek.findIndex((w) => w.dayOfWeek === row.original.dayOfWeek);
                  return (
                    <input
                      type="time"
                      value={row.original.startTime2}
                      onChange={(e) => updateWorkWeekRow(idx, "startTime2", e.target.value)}
                      disabled={row.original.dayType === "NON_WORKING"}
                      className="rounded border border-border bg-surface-hover px-2 py-1 text-sm text-text-primary disabled:opacity-40"
                    />
                  );
                },
              },
              {
                accessorKey: "endTime2",
                header: "Afternoon End",
                cell: ({ row }) => {
                  const idx = workWeek.findIndex((w) => w.dayOfWeek === row.original.dayOfWeek);
                  return (
                    <input
                      type="time"
                      value={row.original.endTime2}
                      onChange={(e) => updateWorkWeekRow(idx, "endTime2", e.target.value)}
                      disabled={row.original.dayType === "NON_WORKING"}
                      className="rounded border border-border bg-surface-hover px-2 py-1 text-sm text-text-primary disabled:opacity-40"
                    />
                  );
                },
              },
            ]}
            data={workWeek}
            sortable={false}
          />
          <div className="mt-6 flex justify-end">
            <button
              onClick={handleSaveWorkWeek}
              disabled={saving}
              className="inline-flex items-center gap-2 rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:bg-border"
            >
              <Save size={16} />
              {saving ? "Saving..." : "Save Work Week"}
            </button>
          </div>
        </div>
      )}

      {/* Exceptions Tab */}
      {activeTab === "exceptions" && (
        <div className="space-y-6">
          {/* Add Exception Form */}
          <div className="rounded-lg border border-border bg-surface/50 p-6">
            <h3 className="mb-4 text-sm font-semibold text-text-primary">
              Add Exception Day
            </h3>
            <div className="grid grid-cols-3 gap-4">
              <div>
                <label className="block text-xs font-medium text-text-secondary">
                  Date *
                </label>
                <input
                  type="date"
                  value={excDate}
                  onChange={(e) => setExcDate(e.target.value)}
                  className={inputClass}
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-text-secondary">
                  Name
                </label>
                <input
                  type="text"
                  value={excName}
                  onChange={(e) => setExcName(e.target.value)}
                  placeholder="e.g., Christmas"
                  className={inputClass}
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-text-secondary">
                  Day Type
                </label>
                <select
                  value={excDayType}
                  onChange={(e) => setExcDayType(e.target.value as typeof excDayType)}
                  className={inputClass}
                >
                  <option value="NON_WORKING">Non-Working (Holiday)</option>
                  <option value="WORKING">Working (Makeup Day)</option>
                  <option value="EXCEPTION_WORKING">Exception Working</option>
                  <option value="EXCEPTION_NON_WORKING">
                    Exception Non-Working
                  </option>
                </select>
              </div>
            </div>
            {(excDayType === "WORKING" ||
              excDayType === "EXCEPTION_WORKING") && (
              <div className="mt-4 grid grid-cols-4 gap-4">
                <div>
                  <label className="block text-xs font-medium text-text-secondary">
                    Morning Start
                  </label>
                  <input
                    type="time"
                    value={excStart1}
                    onChange={(e) => setExcStart1(e.target.value)}
                    className={inputClass}
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-text-secondary">
                    Morning End
                  </label>
                  <input
                    type="time"
                    value={excEnd1}
                    onChange={(e) => setExcEnd1(e.target.value)}
                    className={inputClass}
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-text-secondary">
                    Afternoon Start
                  </label>
                  <input
                    type="time"
                    value={excStart2}
                    onChange={(e) => setExcStart2(e.target.value)}
                    className={inputClass}
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-text-secondary">
                    Afternoon End
                  </label>
                  <input
                    type="time"
                    value={excEnd2}
                    onChange={(e) => setExcEnd2(e.target.value)}
                    className={inputClass}
                  />
                </div>
              </div>
            )}
            <div className="mt-4">
              <button
                onClick={handleAddException}
                disabled={saving}
                className="inline-flex items-center gap-2 rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:bg-border"
              >
                <Plus size={16} />
                {saving ? "Adding..." : "Add Exception"}
              </button>
            </div>
          </div>

          {/* Exceptions List */}
          <div className="rounded-lg border border-border bg-surface/50 p-6">
            <h3 className="mb-4 text-sm font-semibold text-text-primary">
              Exceptions ({now.getFullYear()})
            </h3>
            {exceptions.length === 0 ? (
              <p className="text-sm text-text-muted">
                No exceptions defined for this year.
              </p>
            ) : (
              <div className="space-y-2">
                {exceptions.map((exc) => (
                  <div
                    key={exc.id}
                    className="flex items-center justify-between rounded-md border border-border px-4 py-3"
                  >
                    <div className="flex items-center gap-4">
                      <span className="text-sm font-medium text-text-primary">
                        {exc.exceptionDate}
                      </span>
                      <span className="text-sm text-text-secondary">
                        {exc.name || "—"}
                      </span>
                      <span
                        className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                          exc.dayType === "NON_WORKING" ||
                          exc.dayType === "EXCEPTION_NON_WORKING"
                            ? "bg-red-500/20 text-danger"
                            : "bg-success/20 text-success"
                        }`}
                      >
                        {exc.dayType.replace(/_/g, " ")}
                      </span>
                      {exc.startTime1 && (
                        <span className="text-xs text-text-muted">
                          {exc.startTime1}–{exc.endTime1}
                          {exc.startTime2 ? `, ${exc.startTime2}–${exc.endTime2}` : ""}
                        </span>
                      )}
                    </div>
                    <button
                      onClick={() => handleRemoveException(exc.id)}
                      className="rounded p-1 text-text-muted hover:bg-danger/10 hover:text-danger"
                    >
                      <Trash2 size={14} />
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
