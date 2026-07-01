import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, fireEvent, cleanup } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ProjectCurrencyProvider } from "@/lib/currency/ProjectCurrencyProvider";
import {
  SubContractorsPanel,
  buildActivityNameMap,
} from "../SubContractorsPanel";
import type { ProjectSubContractorSummaryResponse } from "@/lib/api/procurementApi";

vi.mock("@/lib/api/settingsApi", () => ({
  settingsApi: { listCurrencies: vi.fn().mockResolvedValue({ data: [] }) },
}));
vi.mock("@/lib/api/procurementApi", () => ({
  procurementApi: { subContractors: vi.fn(), vendors: vi.fn() },
}));
vi.mock("@/lib/api/activityApi", () => ({
  activityApi: { listActivities: vi.fn() },
}));

import { procurementApi } from "@/lib/api/procurementApi";
import { activityApi } from "@/lib/api/activityApi";

const subContractors = procurementApi.subContractors as unknown as ReturnType<
  typeof vi.fn
>;
const listActivities = activityApi.listActivities as unknown as ReturnType<
  typeof vi.fn
>;

afterEach(cleanup);

function renderPanel() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <ProjectCurrencyProvider currency="INR">
        <SubContractorsPanel projectId="p1" />
      </ProjectCurrencyProvider>
    </QueryClientProvider>,
  );
}

const sampleRow: ProjectSubContractorSummaryResponse = {
  subContractorMasterId: "sc1",
  code: "SC-001",
  name: "Alpha Earthworks",
  location: "Muscat",
  primaryContactName: "Sami",
  primaryContactNumber: "9000",
  assignmentCount: 2,
  plannedCost: 120000,
  actualCost: 90000,
  costVariance: 30000,
  percentComplete: 75,
  lines: [
    {
      activityId: "a1",
      workTypeName: "Excavation",
      unit: "CU_M",
      plannedUnits: 100,
      ratePerUnit: 1200,
      plannedCost: 120000,
      actualUnits: 75,
      actualCost: 90000,
    },
  ],
};

describe("buildActivityNameMap", () => {
  it("maps activity id to name and tolerates undefined", () => {
    expect(buildActivityNameMap(undefined).size).toBe(0);
    expect(buildActivityNameMap([{ id: "a1", name: "Subgrade" }]).get("a1")).toBe(
      "Subgrade",
    );
  });
});

describe("SubContractorsPanel", () => {
  it("renders a sub-contractor row with money via the project currency", async () => {
    subContractors.mockResolvedValue({ data: [sampleRow] });
    listActivities.mockResolvedValue({
      data: { content: [{ id: "a1", name: "Subgrade" }] },
    });
    renderPanel();
    expect(await screen.findByText("Alpha Earthworks")).toBeInTheDocument();
    expect(screen.getByText("SC-001")).toBeInTheDocument();
    expect(screen.getByText(/₹1,20,000/)).toBeInTheDocument(); // planned
    expect(screen.getByText(/₹90,000/)).toBeInTheDocument(); // actual
    expect(screen.getByText(/₹30,000/)).toBeInTheDocument(); // variance (positive = under budget)
    expect(screen.getByText("75.0%")).toBeInTheDocument();
  });

  it("expands a row to reveal assignment lines with the enriched activity name", async () => {
    subContractors.mockResolvedValue({ data: [sampleRow] });
    listActivities.mockResolvedValue({
      data: { content: [{ id: "a1", name: "Subgrade" }] },
    });
    renderPanel();
    fireEvent.click(await screen.findByText("Alpha Earthworks"));
    expect(await screen.findByText("Subgrade")).toBeInTheDocument();
    expect(screen.getByText("Excavation")).toBeInTheDocument();
  });

  it("shows the empty state when no sub-contractors are engaged", async () => {
    subContractors.mockResolvedValue({ data: [] });
    listActivities.mockResolvedValue({ data: { content: [] } });
    renderPanel();
    expect(
      await screen.findByText("No sub-contractors engaged"),
    ).toBeInTheDocument();
  });
});
