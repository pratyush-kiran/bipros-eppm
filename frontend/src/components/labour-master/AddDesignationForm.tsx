"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import toast from "react-hot-toast";
import {
  labourMasterApi,
  type LabourCategory,
  type LabourGrade,
  type NationalityType,
} from "@/lib/api/labourMasterApi";
import { getErrorMessage } from "@/lib/utils/error";

const CATEGORIES: { value: LabourCategory; prefix: string; label: string }[] = [
  { value: "SITE_MANAGEMENT",     prefix: "SM", label: "Site Management" },
  { value: "PLANT_EQUIPMENT",     prefix: "PO", label: "Plant & Equipment Operators" },
  { value: "SKILLED_LABOUR",      prefix: "SL", label: "Skilled Labour" },
  { value: "SEMI_SKILLED_LABOUR", prefix: "SS", label: "Semi-Skilled Labour" },
  { value: "GENERAL_UNSKILLED",   prefix: "GL", label: "General / Unskilled Labour" },
];
const GRADES: LabourGrade[] = ["A", "B", "C", "D", "E"];
const NATIONALITIES: NationalityType[] = ["OMANI", "EXPAT", "OMANI_OR_EXPAT"];

const inputCls =
  "w-full rounded-md border border-hairline bg-ivory px-3 py-2 text-[13px] text-charcoal placeholder:text-ash focus:border-gold/50 focus:outline-none focus:ring-1 focus:ring-gold/40";

export function AddDesignationForm() {
  const router = useRouter();
  const qc = useQueryClient();

  const [category, setCategory] = useState<LabourCategory>("SITE_MANAGEMENT");
  const [code, setCode] = useState("SM-001");
  const [designation, setDesignation] = useState("");
  const [trade, setTrade] = useState("");
  const [grade, setGrade] = useState<LabourGrade>("C");
  const [nationality, setNationality] = useState<NationalityType>("EXPAT");
  const [experienceYearsMin, setExperience] = useState(1);
  const [defaultDailyRate, setRate] = useState(0);
  const [skillsCsv, setSkillsCsv] = useState("");
  const [certsCsv, setCertsCsv] = useState("");
  const [error, setError] = useState<string | null>(null);

  const onCategoryChange = (c: LabourCategory) => {
    setCategory(c);
    const prefix = CATEGORIES.find((x) => x.value === c)!.prefix;
    const suffix = (code.split("-")[1] ?? "001").padStart(3, "0");
    setCode(`${prefix}-${suffix}`);
  };

  const create = useMutation({
    mutationFn: () =>
      labourMasterApi.designations.create({
        code,
        designation,
        category,
        trade,
        grade,
        nationality,
        experienceYearsMin,
        defaultDailyRate,
        skills: skillsCsv.split(",").map((s) => s.trim()).filter(Boolean),
        certifications: certsCsv.split(",").map((s) => s.trim()).filter(Boolean),
      }),
    onSuccess: (res) => {
      qc.invalidateQueries({ queryKey: ["labour-designations"] });
      qc.invalidateQueries({ queryKey: ["labour-deployments"] });
      const d = res.data;
      if (d) {
        toast.success(`Designation "${d.code} – ${d.designation}" saved. Deploy it to a project to see it in the Table.`);
      } else {
        toast.success("Designation saved.");
      }
      router.push("/labour-master/cards");
    },
    onError: (e: unknown) => setError(getErrorMessage(e, "Failed to save designation")),
  });

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        setError(null);
        create.mutate();
      }}
      className="grid max-w-3xl grid-cols-1 gap-5 rounded-xl border border-hairline bg-paper p-6 md:grid-cols-2"
    >
      <Field label="Worker code">
        <input
          value={code}
          onChange={(e) => setCode(e.target.value.toUpperCase())}
          pattern="^(SM|PO|SL|SS|GL)-\d{3}$"
          required
          className={`${inputCls} font-mono`}
        />
      </Field>
      <Field label="Designation">
        <input value={designation} onChange={(e) => setDesignation(e.target.value)} required className={inputCls} />
      </Field>
      <Field label="Category">
        <select
          value={category}
          onChange={(e) => onCategoryChange(e.target.value as LabourCategory)}
          className={inputCls}
        >
          {CATEGORIES.map((c) => (
            <option key={c.value} value={c.value}>{c.label}</option>
          ))}
        </select>
      </Field>
      <Field label="Trade">
        <input value={trade} onChange={(e) => setTrade(e.target.value)} required className={inputCls} />
      </Field>
      <Field label="Grade">
        <select value={grade} onChange={(e) => setGrade(e.target.value as LabourGrade)} className={inputCls}>
          {GRADES.map((g) => (
            <option key={g} value={g}>{g}</option>
          ))}
        </select>
      </Field>
      <Field label="Nationality">
        <select
          value={nationality}
          onChange={(e) => setNationality(e.target.value as NationalityType)}
          className={inputCls}
        >
          {NATIONALITIES.map((n) => (
            <option key={n} value={n}>{n.replace(/_/g, " / ")}</option>
          ))}
        </select>
      </Field>
      <Field label="Experience (years)">
        <input
          type="number"
          min={0}
          value={experienceYearsMin}
          onChange={(e) => setExperience(Number(e.target.value))}
          className={inputCls}
        />
      </Field>
      <Field label="Daily rate (OMR)">
        <input
          type="number"
          min={0}
          step="0.01"
          value={defaultDailyRate}
          onChange={(e) => setRate(Number(e.target.value))}
          className={inputCls}
        />
      </Field>
      <Field label="Skills (comma-separated)">
        <textarea
          rows={2}
          value={skillsCsv}
          onChange={(e) => setSkillsCsv(e.target.value)}
          className={inputCls}
        />
      </Field>
      <Field label="Certifications (comma-separated)">
        <textarea
          rows={2}
          value={certsCsv}
          onChange={(e) => setCertsCsv(e.target.value)}
          className={inputCls}
        />
      </Field>
      {error && (
        <div className="md:col-span-2 rounded-md border border-burgundy/40 bg-burgundy/10 px-3 py-2 text-[12px] text-burgundy">
          {error}
        </div>
      )}
      <div className="md:col-span-2 flex items-center gap-2 border-t border-hairline pt-4">
        <button
          type="submit"
          disabled={create.isPending}
          className="inline-flex items-center justify-center rounded-md bg-gold px-4 py-2 text-[13px] font-semibold text-charcoal transition hover:bg-gold-deep disabled:opacity-60"
        >
          {create.isPending ? "Saving…" : "Save designation"}
        </button>
        <button
          type="button"
          onClick={() => router.back()}
          className="inline-flex items-center justify-center rounded-md border border-hairline px-4 py-2 text-[13px] text-slate hover:bg-ivory hover:text-charcoal"
        >
          Cancel
        </button>
      </div>
    </form>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="space-y-1.5">
      <span className="block text-[10px] font-semibold uppercase tracking-[0.12em] text-slate">{label}</span>
      {children}
    </label>
  );
}
