"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ArrowLeft, Library } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Field, FieldError, FieldHint, Input, Label } from "@/components/ui/input";
import { cn } from "@/lib/utils/cn";
import { hdsApi, type HdsDiscipline } from "@/lib/api/hdsApi";

const DISCIPLINES: { value: HdsDiscipline; label: string }[] = [
  { value: "HIGHWAY", label: "Highway" },
  { value: "BRIDGE", label: "Bridge" },
  { value: "GEOTECH", label: "Geotechnical" },
  { value: "PAVEMENT", label: "Pavement" },
  { value: "TRAFFIC", label: "Traffic" },
  { value: "DRAINAGE", label: "Drainage" },
  { value: "OTHER", label: "Other" },
];

export default function NewHdsPublicationPage() {
  const router = useRouter();
  const [title, setTitle] = useState("");
  const [shortCode, setShortCode] = useState("");
  const [discipline, setDiscipline] = useState<HdsDiscipline>("HIGHWAY");
  const [issuingAuthority, setIssuingAuthority] = useState("");
  const [country, setCountry] = useState("");
  const [description, setDescription] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const doc = await hdsApi.createDocument({
        title,
        shortCode,
        discipline,
        issuingAuthority: issuingAuthority || undefined,
        country: country || undefined,
        description: description || undefined,
      });
      router.push(`/admin/hds-library/${doc.id}`);
    } catch (e: unknown) {
      const msg =
        (e as { response?: { data?: { error?: { message?: string } } } })?.response?.data
          ?.error?.message ?? (e instanceof Error ? e.message : String(e));
      setError(msg);
      setBusy(false);
    }
  };

  return (
    <div className="p-6 lg:p-8">
      {/* Breadcrumb back */}
      <Link
        href="/admin/hds-library"
        className="mb-4 inline-flex items-center gap-1.5 text-xs font-medium text-slate transition-colors hover:text-gold-deep"
      >
        <ArrowLeft size={12} strokeWidth={1.75} />
        HDS Library
      </Link>

      {/* Title */}
      <div className="mb-8">
        <div className="mb-2 flex items-center gap-2 text-[10px] font-semibold uppercase tracking-[0.16em] text-gold-deep">
          <Library size={12} strokeWidth={1.75} /> New publication
        </div>
        <h1 className="font-display text-3xl font-medium tracking-tight text-charcoal">
          Catalogue a Design Standard
        </h1>
        <p className="mt-2 max-w-xl text-sm text-slate">
          A publication is the umbrella for one or more PDF revisions. Add metadata first;
          upload PDFs from the publication&apos;s page next.
        </p>
      </div>

      <div className="max-w-3xl">
        <Card variant="flat" className="p-7">
          <div className="grid gap-5 sm:grid-cols-2">
            <Field className="sm:col-span-2">
              <Label htmlFor="title">Title *</Label>
              <Input
                id="title"
                value={title}
                onChange={e => setTitle(e.target.value)}
                placeholder="Highway Design Standard, Volume 3 — Geometric Design"
              />
            </Field>

            <Field>
              <Label htmlFor="shortCode">
                Short code <span className="text-ash font-normal">*</span>
              </Label>
              <Input
                id="shortCode"
                value={shortCode}
                onChange={e => setShortCode(e.target.value.toUpperCase())}
                placeholder="HDS-V3"
                className="font-mono"
              />
              <FieldHint>Used in citations. Must be unique.</FieldHint>
            </Field>

            <Field>
              <Label htmlFor="country">Country (ISO-3166)</Label>
              <Input
                id="country"
                value={country}
                onChange={e => setCountry(e.target.value.toUpperCase())}
                maxLength={2}
                placeholder="OM"
                className="font-mono uppercase"
              />
              <FieldHint>Two-letter code, e.g. OM, IN, AE.</FieldHint>
            </Field>

            <Field className="sm:col-span-2">
              <Label>Discipline *</Label>
              <div className="mt-1 flex flex-wrap gap-2">
                {DISCIPLINES.map(d => {
                  const active = discipline === d.value;
                  return (
                    <button
                      key={d.value}
                      type="button"
                      onClick={() => setDiscipline(d.value)}
                      className={cn(
                        "rounded-full border px-3.5 py-1.5 text-xs font-medium transition-all",
                        active
                          ? "border-gold bg-gold-tint text-gold-ink shadow-[0_0_0_3px_rgba(212,175,55,0.18)]"
                          : "border-divider bg-paper text-slate hover:border-gold-deep/40 hover:text-charcoal",
                      )}
                    >
                      {d.label}
                    </button>
                  );
                })}
              </div>
            </Field>

            <Field className="sm:col-span-2">
              <Label htmlFor="authority">Issuing authority</Label>
              <Input
                id="authority"
                value={issuingAuthority}
                onChange={e => setIssuingAuthority(e.target.value)}
                placeholder="Sultanate of Oman, Ministry of Transport"
              />
            </Field>

            <Field className="sm:col-span-2">
              <Label htmlFor="description">Description</Label>
              <textarea
                id="description"
                value={description}
                onChange={e => setDescription(e.target.value)}
                rows={3}
                className="flex w-full rounded-[10px] border border-divider bg-paper px-3.5 py-2 text-sm text-charcoal placeholder:text-ash transition-all duration-[120ms] hover:border-gold-deep/50 focus-visible:border-gold focus-visible:shadow-[0_0_0_3px_rgba(212,175,55,0.18)] focus-visible:outline-none"
                placeholder="Optional notes for librarians."
              />
            </Field>

            {error && (
              <div className="sm:col-span-2">
                <FieldError>{error}</FieldError>
              </div>
            )}

            <div className="flex items-center gap-2 sm:col-span-2">
              <Button
                variant="primary"
                size="md"
                onClick={submit}
                disabled={busy || !title || !shortCode}
              >
                {busy ? "Creating…" : "Create publication"}
              </Button>
              <Link href="/admin/hds-library">
                <Button variant="ghost" size="md" type="button">
                  Cancel
                </Button>
              </Link>
            </div>
          </div>
        </Card>
      </div>
    </div>
  );
}
