import { describe, it, expect, vi, beforeEach } from "vitest";

vi.mock("./client", () => ({
  apiClient: { get: vi.fn() },
}));

import { apiClient } from "./client";
import { procurementApi } from "./procurementApi";

const mockedGet = apiClient.get as unknown as ReturnType<typeof vi.fn>;

describe("procurementApi", () => {
  beforeEach(() => mockedGet.mockReset());

  it("subContractors GETs the project sub-contractors endpoint and unwraps .data", async () => {
    mockedGet.mockResolvedValue({ data: { success: true, data: [] } });
    const res = await procurementApi.subContractors("p1");
    expect(mockedGet).toHaveBeenCalledWith(
      "/v1/projects/p1/procurement/sub-contractors",
    );
    expect(res).toEqual({ success: true, data: [] });
  });

  it("vendors GETs the project vendors endpoint and unwraps .data", async () => {
    mockedGet.mockResolvedValue({ data: { success: true, data: [] } });
    const res = await procurementApi.vendors("p1");
    expect(mockedGet).toHaveBeenCalledWith("/v1/projects/p1/procurement/vendors");
    expect(res).toEqual({ success: true, data: [] });
  });
});
