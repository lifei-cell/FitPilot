import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { WorkoutFeedbackForm } from "./WorkoutFeedbackForm";

describe("WorkoutFeedbackForm", () => {
  it("submits structured fatigue and pain feedback", async () => {
    const submit = vi.fn();
    const user = userEvent.setup();
    render(<WorkoutFeedbackForm busy={false} onSubmit={submit} onSkip={vi.fn()} />);
    fireEvent.change(screen.getByRole("slider", { name: "疲劳" }), { target: { value: "8" } });
    fireEvent.change(screen.getByRole("slider", { name: "疼痛" }), { target: { value: "2" } });
    await user.type(screen.getByPlaceholderText(/可选/), "左膝轻微紧张");
    await user.click(screen.getByRole("button", { name: "提交并完成" }));
    expect(submit).toHaveBeenCalledWith({ fatigueScore: 8, painScore: 2, notes: "左膝轻微紧张" });
  });

  it("allows feedback to be skipped", async () => {
    const skip = vi.fn();
    const user = userEvent.setup();
    render(<WorkoutFeedbackForm busy={false} onSubmit={vi.fn()} onSkip={skip} />);
    await user.click(screen.getByRole("button", { name: "跳过并完成" }));
    expect(skip).toHaveBeenCalledOnce();
  });
});
