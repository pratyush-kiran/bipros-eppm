import { describe, it, expect } from "vitest";
import { getPriorityInfo, PRIORITY_LABELS, PRIORITY_CHOICES } from "../format";

describe("getPriorityInfo — priority bucketing", () => {
  it("maps the New Project form's priority values to the six display labels", () => {
    // Values offered by the project-creation dropdown: one per bucket, [5, 20, 35, 50, 70, 90].
    expect(getPriorityInfo(5).label).toBe("Critical");
    expect(getPriorityInfo(20).label).toBe("Very High");
    expect(getPriorityInfo(35).label).toBe("High");
    expect(getPriorityInfo(50).label).toBe("Medium");
    expect(getPriorityInfo(70).label).toBe("Low");
    expect(getPriorityInfo(90).label).toBe("Very Low");
  });

  it("keeps the inclusive bucket boundaries", () => {
    expect(getPriorityInfo(10).label).toBe("Critical");
    expect(getPriorityInfo(11).label).toBe("Very High");
    expect(getPriorityInfo(25).label).toBe("Very High");
    expect(getPriorityInfo(26).label).toBe("High");
    expect(getPriorityInfo(60).label).toBe("Medium");
    expect(getPriorityInfo(61).label).toBe("Low");
    expect(getPriorityInfo(100).label).toBe("Very Low");
  });

  it("returns a dash for null / undefined / NaN", () => {
    expect(getPriorityInfo(null).label).toBe("—");
    expect(getPriorityInfo(undefined).label).toBe("—");
    expect(getPriorityInfo(NaN).label).toBe("—");
  });
});

describe("PRIORITY_LABELS — project-list filter options", () => {
  it("lists the six buckets highest → lowest", () => {
    expect([...PRIORITY_LABELS]).toEqual([
      "Critical",
      "Very High",
      "High",
      "Medium",
      "Low",
      "Very Low",
    ]);
  });

  it("every creatable priority resolves to a label the filter offers (filter ⇔ display consistency)", () => {
    for (const { value } of PRIORITY_CHOICES) {
      expect(PRIORITY_LABELS).toContain(getPriorityInfo(value).label);
    }
  });
});

describe("PRIORITY_CHOICES — project create/edit dropdown options", () => {
  it("offers exactly one option per bucket label, highest → lowest (no duplicate 'Low')", () => {
    expect(PRIORITY_CHOICES.map((c) => c.label)).toEqual([...PRIORITY_LABELS]);
  });

  it("each option's stored value round-trips back to its own label", () => {
    for (const { value, label } of PRIORITY_CHOICES) {
      expect(getPriorityInfo(value).label).toBe(label);
    }
  });
});
