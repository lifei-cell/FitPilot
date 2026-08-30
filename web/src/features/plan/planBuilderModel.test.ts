import { describe, expect, it } from "vitest";
import type { Plan } from "../../api/types";
import {
  initialPlanDraft,
  planToDraft,
  toPlanCreateInput,
  validatePlanDraft,
} from "./planBuilderModel";

describe("plan builder model", () => {
  it("rejects days without exercises", () => {
    const draft = initialPlanDraft();
    draft.name = "力量计划";

    expect(validatePlanDraft(draft)).toContain("至少需要添加一个动作");
  });

  it("round-trips an existing plan without inventing optional targets", () => {
    const plan: Plan = {
      id: 42,
      name: "现有计划",
      description: "保持原始训练参数",
      goal: "STRENGTH",
      durationWeeks: 8,
      daysPerWeek: 1,
      status: "DRAFT",
      version: 3,
      days: [
        {
          id: 11,
          dayNumber: 1,
          name: "力量日",
          exercises: [
            {
              id: 21,
              exerciseId: 1,
              exerciseName: "杠铃卧推",
              sequence: 1,
              targetSets: 5,
              targetRepsMin: 3,
              targetRepsMax: 5,
            },
          ],
        },
      ],
    };

    const draft = planToDraft(plan);
    const payload = toPlanCreateInput(draft);

    expect(draft.days[0].exercises[0].exercise.name).toBe("杠铃卧推");
    expect(payload.days[0].exercises[0]).toMatchObject({
      exerciseId: 1,
      sequence: 1,
      targetSets: 5,
      targetRpe: undefined,
      restSeconds: undefined,
    });
  });
});
