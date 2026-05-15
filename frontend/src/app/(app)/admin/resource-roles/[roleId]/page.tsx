"use client";

import { useParams } from "next/navigation";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { ArrowLeft } from "lucide-react";
import { resourceRoleApi } from "@/lib/api/resourceRoleApi";
import { ManpowerRoleRateTable } from "@/components/role/ManpowerRoleRateTable";
import { EquipmentRoleVariantTable } from "@/components/role/EquipmentRoleVariantTable";
import { MaterialRoleVariantTable } from "@/components/role/MaterialRoleVariantTable";

export default function ResourceRoleDetailPage() {
  const params = useParams<{ roleId: string }>();
  const roleId = params?.roleId as string;

  const { data: roleResp, isLoading } = useQuery({
    queryKey: ["resource-role", roleId],
    queryFn: () => resourceRoleApi.get(roleId),
    enabled: !!roleId,
  });

  const role = roleResp?.data;

  if (isLoading) return <div className="p-6 text-neutral-400">Loading…</div>;
  if (!role) return <div className="p-6 text-red-400">Role not found.</div>;

  const typeCode = role.resourceTypeCode?.toUpperCase();
  const isManpower = typeCode === "LABOR" || typeCode === "MANPOWER";
  const isEquipment = typeCode === "EQUIPMENT";
  const isMaterial = typeCode === "MATERIAL";

  return (
    <div className="p-6 space-y-6">
      <Link
        href="/admin/resource-roles"
        className="inline-flex items-center gap-1 text-sm text-text-secondary hover:text-text-primary"
      >
        <ArrowLeft className="h-4 w-4" /> Back to roles
      </Link>

      <div>
        <div className="text-xs uppercase tracking-wide text-text-muted">
          {role.resourceTypeName}
        </div>
        <h1 className="text-3xl font-semibold">{role.name}</h1>
        <div className="mt-1 text-sm text-text-secondary">{role.code}</div>
        {role.description && (
          <p className="mt-2 text-sm text-text-primary">{role.description}</p>
        )}
      </div>

      <div className="rounded-md border border-border p-4">
        {isManpower && <ManpowerRoleRateTable roleId={roleId} />}
        {isEquipment && <EquipmentRoleVariantTable roleId={roleId} />}
        {isMaterial && <MaterialRoleVariantTable roleId={roleId} />}
        {!isManpower && !isEquipment && !isMaterial && (
          <div className="text-text-secondary">
            Rate configuration is only available for Manpower / Equipment / Material roles.
          </div>
        )}
      </div>
    </div>
  );
}
