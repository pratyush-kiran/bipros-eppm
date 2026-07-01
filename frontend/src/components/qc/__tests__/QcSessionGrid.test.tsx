import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, fireEvent, cleanup } from "@testing-library/react";
import { QcSessionGrid } from "../QcSessionGrid";
import type { QcSession } from "@/lib/types/qc";

afterEach(cleanup);

function sessionWith(outcome: "PASS" | "FAIL"): QcSession {
  return {
    id: "s1", projectId: "p1", activityId: "a1", activityName: "Subgrade",
    testDate: "2026-03-27", chainageFrom: "45+000", chainageTo: "46+000",
    items: [{ id: "i1", testTypeId: "t1", testTypeName: "CBR (Soil)", sampleRefNo: "266I-CBRS-003",
      testResult: 7.6, requiredIrc: 8, outcome, labInspector: "Field Lab" }],
  } as unknown as QcSession;
}

describe("QcSessionGrid Raise NCR", () => {
  it("shows Raise NCR only on FAIL rows and fires the callback", () => {
    const onRaiseNcr = vi.fn();
    render(<QcSessionGrid sessions={[sessionWith("FAIL")]} onEdit={() => {}} onDelete={() => {}} onRaiseNcr={onRaiseNcr} />);
    const btn = screen.getByRole("button", { name: /raise ncr/i });
    fireEvent.click(btn);
    expect(onRaiseNcr).toHaveBeenCalledTimes(1);
  });

  it("hides Raise NCR on PASS rows", () => {
    render(<QcSessionGrid sessions={[sessionWith("PASS")]} onEdit={() => {}} onDelete={() => {}} onRaiseNcr={() => {}} />);
    expect(screen.queryByRole("button", { name: /raise ncr/i })).toBeNull();
  });
});
