import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import {
  ProductivityPreviewBanner,
  type ProductivityPreviewData,
} from "./ProductivityPreviewBanner";

const basePreview: ProductivityPreviewData = {
  expectedFromManpower: 117.65,
  expectedFromEquipment: 252.45,
  expectedBottleneck: 117.65,
  source: "BOTH",
  coverage: "BOTH",
  normCombination: "SERIES",
  warnings: [],
};

describe("ProductivityPreviewBanner", () => {
  it("renders deviation against raw workdone when scQty is 0", () => {
    render(
      <ProductivityPreviewBanner
        preview={basePreview}
        workdone={540}
        subContractorQty={0}
        unit="Nos"
      />,
    );
    expect(screen.queryByText(/Sub-contractor/)).toBeNull();
    expect(screen.getByText(/deviates by/)).toBeInTheDocument();
  });

  it("renders breakdown line when 0 < scQty < workdone", () => {
    render(
      <ProductivityPreviewBanner
        preview={basePreview}
        workdone={540}
        subContractorQty={340}
        unit="Nos"
      />,
    );
    // Breakdown line should be visible with effective M+E figure.
    expect(screen.getByText(/Effective M\+E/)).toBeInTheDocument();
    // Effective workdone = 540 - 340 = 200; expected bottleneck = 117.65.
    // |200 - 117.65| / 117.65 ≈ 0.7001 → rounds to 70% (> 25% threshold, so warn fires).
    // Critically, the deviation uses the EFFECTIVE workdone (200), not the raw 540.
    expect(screen.getByText(/deviates by ~70%/)).toBeInTheDocument();
    // The warning span explicitly mentions the effective value (200), not the raw workdone.
    expect(
      screen.getByText(/Effective workdone \(200\)/),
    ).toBeInTheDocument();
  });

  it("renders 'all delivered by sub-contractor' when scQty == workdone", () => {
    render(
      <ProductivityPreviewBanner
        preview={basePreview}
        workdone={540}
        subContractorQty={540}
        unit="Nos"
      />,
    );
    expect(
      screen.getByText(/All work delivered by sub-contractor/),
    ).toBeInTheDocument();
    expect(screen.queryByText(/deviates by/)).toBeNull();
  });
});
