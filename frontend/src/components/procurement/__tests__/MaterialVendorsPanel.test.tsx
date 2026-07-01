import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, cleanup } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ProjectCurrencyProvider } from "@/lib/currency/ProjectCurrencyProvider";
import {
  MaterialVendorsPanel,
  buildSupplierMap,
  resolveVendorName,
} from "../MaterialVendorsPanel";
import type { ProjectVendorSummaryResponse } from "@/lib/api/procurementApi";

vi.mock("@/lib/api/settingsApi", () => ({
  settingsApi: { listCurrencies: vi.fn().mockResolvedValue({ data: [] }) },
}));
vi.mock("@/lib/api/procurementApi", () => ({
  procurementApi: { subContractors: vi.fn(), vendors: vi.fn() },
}));
vi.mock("@/lib/api/organisationApi", () => ({
  organisationApi: { listByType: vi.fn() },
}));

import { procurementApi } from "@/lib/api/procurementApi";
import { organisationApi } from "@/lib/api/organisationApi";

const vendors = procurementApi.vendors as unknown as ReturnType<typeof vi.fn>;
const listByType = organisationApi.listByType as unknown as ReturnType<
  typeof vi.fn
>;

afterEach(cleanup);

function renderPanel() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <ProjectCurrencyProvider currency="INR">
        <MaterialVendorsPanel projectId="p1" />
      </ProjectCurrencyProvider>
    </QueryClientProvider>,
  );
}

const knownVendor: ProjectVendorSummaryResponse = {
  supplierOrganisationId: "org1",
  materialCount: 3,
  receiptCount: 5,
  totalValueReceived: 250000,
  lastReceiptDate: "2026-06-20",
  receipts: [],
  materials: [],
};

const unassignedVendor: ProjectVendorSummaryResponse = {
  supplierOrganisationId: null,
  materialCount: 0,
  receiptCount: 1,
  totalValueReceived: 40000,
  lastReceiptDate: null,
  receipts: [],
  materials: [],
};

describe("buildSupplierMap / resolveVendorName", () => {
  it("resolves a known supplier id to its name", () => {
    const map = buildSupplierMap([
      { id: "org1", name: "Cementico", code: "SUP-1" },
    ]);
    expect(resolveVendorName("org1", map)).toBe("Cementico");
  });
  it("labels a null supplier id as Unassigned vendor", () => {
    expect(resolveVendorName(null, buildSupplierMap([]))).toBe(
      "Unassigned vendor",
    );
  });
  it("labels an unresolved supplier id as Unknown vendor", () => {
    expect(resolveVendorName("ghost", buildSupplierMap([]))).toBe(
      "Unknown vendor",
    );
  });
});

describe("MaterialVendorsPanel", () => {
  it("renders a vendor row with the resolved name and money via the project currency", async () => {
    vendors.mockResolvedValue({ data: [knownVendor] });
    listByType.mockResolvedValue({
      data: [{ id: "org1", name: "Cementico", code: "SUP-1" }],
    });
    renderPanel();
    expect(await screen.findByText("Cementico")).toBeInTheDocument();
    expect(screen.getByText(/₹2,50,000/)).toBeInTheDocument();
  });

  it("groups a null-supplier receipt under Unassigned vendor", async () => {
    vendors.mockResolvedValue({ data: [unassignedVendor] });
    listByType.mockResolvedValue({ data: [] });
    renderPanel();
    expect(await screen.findByText("Unassigned vendor")).toBeInTheDocument();
    expect(screen.getByText(/₹40,000/)).toBeInTheDocument();
  });

  it("shows the empty state when there are no vendor receipts", async () => {
    vendors.mockResolvedValue({ data: [] });
    listByType.mockResolvedValue({ data: [] });
    renderPanel();
    expect(await screen.findByText("No vendor receipts")).toBeInTheDocument();
  });
});
