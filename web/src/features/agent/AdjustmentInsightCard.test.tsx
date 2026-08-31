import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { AdjustmentInsightCard } from "./AdjustmentInsightCard";

describe("AdjustmentInsightCard", () => {
  it("renders evidence and lets the user reject a pending adjustment", async () => {
    const reject = vi.fn();
    const user = userEvent.setup();
    render(<AdjustmentInsightCard onReject={reject} adjustment={{
      id: "adjustment-1", sourcePlanId: 1, sourcePlanVersion: 2, rule: "DELOAD",
      status: "AWAITING_CONFIRMATION", reasons: ["疲劳偏高"], createdAt: "2026-08-31T10:00:00Z",
      evidence: { windowDays: 28, completedWorkouts: 8, planCompletionRate: .9, setCompletionRate: .95,
        averageRpe: 9.1, feedbackCount: 6, averageFatigue: 8.2, latestPain: 1,
        currentVolume: 9000, previousVolume: 10000, volumeChangeRate: -.1, personalRecords: 0 },
    }} />);
    expect(screen.getByText("90%")).toBeInTheDocument();
    expect(screen.getByText("疲劳偏高")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "拒绝本次调整" }));
    expect(reject).toHaveBeenCalledOnce();
  });
});
