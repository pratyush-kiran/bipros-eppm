"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { getErrorMessage } from "@/lib/utils/error";
import { PageHeader } from "@/components/common/PageHeader";
import { Breadcrumb } from "@/components/common/Breadcrumb";
import { calendarApi } from "@/lib/api/calendarApi";
import type { CreateCalendarRequest } from "@/lib/api/calendarApi";

export default function NewCalendarPage() {
  const router = useRouter();
  const queryClient = useQueryClient();

  const [formData, setFormData] = useState<CreateCalendarRequest & { code: string }>({
    code: "",
    name: "",
    description: "",
    calendarType: "GLOBAL",
    standardWorkHoursPerDay: 8,
    standardWorkDaysPerWeek: 5,
  });

  const [error, setError] = useState("");
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [isSubmitting, setIsSubmitting] = useState(false);

  const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => {
    const { name, value } = e.target;
    if (error) setError("");
    if (fieldErrors[name]) setFieldErrors((prev) => { const next = { ...prev }; delete next[name]; return next; });
    setFormData((prev) => ({
      ...prev,
      [name]:
        name === "standardWorkHoursPerDay" || name === "standardWorkDaysPerWeek" ? parseInt(value, 10) : value,
    }));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");

    const errors: Record<string, string> = {};
    if (!formData.code.trim()) errors.code = "Calendar code is required";
    if (!formData.name.trim()) errors.name = "Calendar name is required";
    if (Object.keys(errors).length > 0) {
      setFieldErrors(errors);
      setError("Please fix the highlighted fields");
      return;
    }

    setIsSubmitting(true);
    try {
      const { code, ...submitData } = formData;
      const result = await calendarApi.createCalendar(submitData);
      if (result.data) {
        // Invalidate the list cache so the new calendar shows immediately —
        // without this the global 5-min staleTime serves the stale list until
        // a hard refresh (see providers.tsx).
        await queryClient.invalidateQueries({ queryKey: ["calendars"] });
        router.push("/admin/calendars");
      }
    } catch (err: unknown) {
      setError(getErrorMessage(err, "Failed to create calendar"));
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div>
      <div className="mb-4">
        <Breadcrumb items={[
          { label: "Calendars", href: "/admin/calendars" },
          { label: "New Calendar", href: "/admin/calendars/new", active: true },
        ]} />
      </div>
      <PageHeader
        title="New Calendar"
        description="Create a new project, resource, or global calendar"
      />

      <div className="rounded-lg border border-border bg-surface/50 p-6 shadow-sm">
        <form onSubmit={handleSubmit} className="space-y-6">
          {error && (
            <div className="flex items-center justify-between rounded-md bg-danger/10 p-4 text-sm text-danger">
              <span>{error}</span>
              <button type="button" onClick={() => setError("")} className="ml-3 text-danger hover:text-danger">&times;</button>
            </div>
          )}

          <div className="grid grid-cols-2 gap-6">
            <div>
              <label className="block text-sm font-medium text-text-secondary">Code *</label>
              <input
                type="text"
                name="code"
                value={formData.code}
                onChange={handleChange}
                className={`mt-1 block w-full rounded-md border ${fieldErrors.code ? "border-danger" : "border-border"} px-3 py-2 text-text-primary placeholder-text-muted focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent`}
                placeholder="e.g., CAL-001"
              />
              {fieldErrors.code && <p className="mt-1 text-xs text-danger">{fieldErrors.code}</p>}
            </div>
            <div>
              <label className="block text-sm font-medium text-text-secondary">Name *</label>
              <input
                type="text"
                name="name"
                value={formData.name}
                onChange={handleChange}
                className={`mt-1 block w-full rounded-md border ${fieldErrors.name ? "border-danger" : "border-border"} px-3 py-2 text-text-primary placeholder-text-muted focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent`}
                placeholder="e.g., Standard 9-5 Calendar"
              />
              {fieldErrors.name && <p className="mt-1 text-xs text-danger">{fieldErrors.name}</p>}
            </div>
            <div className="col-span-2">
              <label className="block text-sm font-medium text-text-secondary">Description</label>
              <input
                type="text"
                name="description"
                value={formData.description || ""}
                onChange={handleChange}
                className="mt-1 block w-full rounded-md border border-border px-3 py-2 text-text-primary placeholder-text-muted focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                placeholder="Optional description"
              />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-6">
            <div>
              <label className="block text-sm font-medium text-text-secondary">Calendar Type *</label>
              <select
                name="calendarType"
                value={formData.calendarType}
                onChange={handleChange}
                className="mt-1 block w-full rounded-md border border-border px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              >
                <option value="GLOBAL">Global</option>
                <option value="PROJECT">Project</option>
                <option value="RESOURCE">Resource</option>
              </select>
            </div>

            <div>
              <label className="block text-sm font-medium text-text-secondary">
                Work Days Per Week
              </label>
              <input
                type="number"
                name="standardWorkDaysPerWeek"
                value={formData.standardWorkDaysPerWeek || 5}
                onChange={handleChange}
                min="1"
                max="7"
                className="mt-1 block w-full rounded-md border border-border px-3 py-2 text-text-primary placeholder-text-muted focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              />
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-text-secondary">
              Work Hours Per Day
            </label>
            <input
              type="number"
              name="standardWorkHoursPerDay"
              value={formData.standardWorkHoursPerDay || 8}
              onChange={handleChange}
              min="1"
              max="24"
              className="mt-1 block w-full rounded-md border border-border px-3 py-2 text-text-primary placeholder-text-muted focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>

          <div className="flex gap-3 pt-6">
            <button
              type="submit"
              disabled={isSubmitting}
              className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:bg-border"
            >
              {isSubmitting ? "Creating..." : "Create Calendar"}
            </button>
            <button
              type="button"
              onClick={() => router.back()}
              className="rounded-md bg-surface-active/50 px-4 py-2 text-sm font-medium text-text-secondary hover:bg-surface-active"
            >
              Cancel
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
