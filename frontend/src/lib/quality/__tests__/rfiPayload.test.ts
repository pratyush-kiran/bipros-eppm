import { describe, it, expect } from "vitest";
import { buildRfiUpdatePayload } from "../rfiPayload";
import type { RfiRegister } from "@/lib/api/documentApi";

const rfi: RfiRegister = {
  id: "r1", projectId: "p1", rfiNumber: "RFI-001", subject: "old",
  description: "d", raisedBy: "Alice", assignedTo: "Bob",
  raisedDate: "2026-03-01", dueDate: "2026-03-10", closedDate: null,
  status: "OPEN", priority: "MEDIUM", response: "", createdAt: "", updatedAt: "",
};

describe("buildRfiUpdatePayload", () => {
  it("carries the immutable required fields the backend validates", () => {
    const payload = buildRfiUpdatePayload(rfi, {
      subject: "new subject", priority: "HIGH", status: "RESPONDED",
      assignedTo: "Carol", dueDate: "2026-03-20", response: "answer", closedDate: null,
    });
    expect(payload.rfiNumber).toBe("RFI-001");
    expect(payload.raisedBy).toBe("Alice");
    expect(payload.raisedDate).toBe("2026-03-01");
    expect(payload.subject).toBe("new subject");
    expect(payload.status).toBe("RESPONDED");
  });
});
