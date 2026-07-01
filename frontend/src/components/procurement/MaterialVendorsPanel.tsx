"use client";

import { Fragment, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ChevronDown, ChevronRight } from "lucide-react";
import { organisationApi } from "@/lib/api/organisationApi";
import { procurementApi } from "@/lib/api/procurementApi";
import { EmptyState } from "@/components/common/EmptyState";
import { PageHeader } from "@/components/common/PageHeader";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";

export function buildSupplierMap(
  suppliers: { id: string; name: string; code: string }[] | null | undefined,
): Map<string, { name: string; code: string }> {
  const map = new Map<string, { name: string; code: string }>();
  for (const s of suppliers ?? []) map.set(s.id, { name: s.name, code: s.code });
  return map;
}

export function resolveVendorName(
  supplierOrganisationId: string | null,
  supplierMap: Map<string, { name: string; code: string }>,
): string {
  if (supplierOrganisationId == null) return "Unassigned vendor";
  return supplierMap.get(supplierOrganisationId)?.name ?? "Unknown vendor";
}

export function MaterialVendorsPanel({ projectId }: { projectId: string }) {
  const { money } = useProjectCurrency();
  const [expanded, setExpanded] = useState<string | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ["procurement-vendors", projectId],
    queryFn: () => procurementApi.vendors(projectId),
    enabled: !!projectId,
  });
  const rows = data?.data ?? [];

  const { data: suppliersData } = useQuery({
    queryKey: ["organisations", "suppliers"],
    queryFn: () => organisationApi.listByType("SUPPLIER"),
  });
  const supplierMap = buildSupplierMap(suppliersData?.data);

  return (
    <>
      <PageHeader
        title="Material Vendors"
        description="Materials received and total value by supplier across this project's goods receipts."
      />

      {isLoading ? (
        <div className="text-sm text-text-muted">Loading…</div>
      ) : rows.length === 0 ? (
        <EmptyState
          title="No vendor receipts"
          description="Goods receipts recorded against this project's suppliers will appear here."
        />
      ) : (
        <div className="overflow-hidden rounded-lg border border-border">
          <table className="w-full text-sm">
            <thead className="bg-surface-hover text-left text-xs font-semibold uppercase tracking-wide text-text-secondary">
              <tr>
                <th className="px-4 py-2">Vendor</th>
                <th className="px-4 py-2 text-right">Materials</th>
                <th className="px-4 py-2 text-right">Receipts</th>
                <th className="px-4 py-2 text-right">Total value</th>
                <th className="px-4 py-2">Last receipt</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => {
                const key = row.supplierOrganisationId ?? "unassigned";
                const isOpen = expanded === key;
                return (
                  <Fragment key={key}>
                    <tr
                      className="cursor-pointer border-t border-border hover:bg-surface-hover/50"
                      onClick={() => setExpanded(isOpen ? null : key)}
                    >
                      <td className="px-4 py-2 font-medium text-text-primary">
                        <span className="flex items-center gap-1.5">
                          {isOpen ? (
                            <ChevronDown className="h-4 w-4 text-text-muted" />
                          ) : (
                            <ChevronRight className="h-4 w-4 text-text-muted" />
                          )}
                          {resolveVendorName(
                            row.supplierOrganisationId,
                            supplierMap,
                          )}
                        </span>
                      </td>
                      <td className="px-4 py-2 text-right text-text-secondary">
                        {row.materialCount}
                      </td>
                      <td className="px-4 py-2 text-right text-text-secondary">
                        {row.receiptCount}
                      </td>
                      <td className="px-4 py-2 text-right text-text-primary">
                        {money(row.totalValueReceived)}
                      </td>
                      <td className="px-4 py-2 text-text-secondary">
                        {row.lastReceiptDate
                          ? new Date(row.lastReceiptDate).toLocaleDateString()
                          : "—"}
                      </td>
                    </tr>
                    {isOpen && (
                      <tr className="border-t border-border bg-surface/40">
                        <td colSpan={5} className="px-4 py-3">
                          <div className="grid gap-4 md:grid-cols-2">
                            <div>
                              <h4 className="mb-1 text-xs font-semibold uppercase tracking-wide text-text-secondary">
                                Receipts
                              </h4>
                              {row.receipts.length === 0 ? (
                                <p className="text-sm text-text-muted">
                                  No receipts.
                                </p>
                              ) : (
                                <table className="w-full text-xs">
                                  <thead className="text-left uppercase tracking-wide text-text-muted">
                                    <tr>
                                      <th className="px-2 py-1">GRN</th>
                                      <th className="px-2 py-1">Date</th>
                                      <th className="px-2 py-1">Material</th>
                                      <th className="px-2 py-1 text-right">Qty</th>
                                      <th className="px-2 py-1 text-right">
                                        Amount
                                      </th>
                                    </tr>
                                  </thead>
                                  <tbody>
                                    {row.receipts.map((r) => (
                                      <tr
                                        key={r.grnId}
                                        className="border-t border-hairline"
                                      >
                                        <td className="px-2 py-1 text-text-primary">
                                          {r.grnNumber}
                                        </td>
                                        <td className="px-2 py-1 text-text-secondary">
                                          {r.receivedDate}
                                        </td>
                                        <td className="px-2 py-1 text-text-secondary">
                                          {r.materialName}
                                        </td>
                                        <td className="px-2 py-1 text-right text-text-secondary">
                                          {r.quantity} {r.unit}
                                        </td>
                                        <td className="px-2 py-1 text-right text-text-primary">
                                          {money(r.amount)}
                                        </td>
                                      </tr>
                                    ))}
                                  </tbody>
                                </table>
                              )}
                            </div>
                            <div>
                              <h4 className="mb-1 text-xs font-semibold uppercase tracking-wide text-text-secondary">
                                Materials
                              </h4>
                              {row.materials.length === 0 ? (
                                <p className="text-sm text-text-muted">
                                  No materials.
                                </p>
                              ) : (
                                <ul className="space-y-1 text-xs text-text-secondary">
                                  {row.materials.map((m) => (
                                    <li key={m.materialId}>
                                      <span className="text-text-primary">
                                        {m.name}
                                      </span>{" "}
                                      <span className="text-text-muted">
                                        {m.code} · {m.category} · {m.unit}
                                      </span>
                                    </li>
                                  ))}
                                </ul>
                              )}
                            </div>
                          </div>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
