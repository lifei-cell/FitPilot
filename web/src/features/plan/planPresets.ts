import { api } from "../../api/client";
import type { Exercise, PageResult } from "../../api/types";
import { newDay, newExercise, type PlanDraft } from "./planBuilderModel";

type PresetExercise = {
  englishName: string;
  targetSets: number;
  targetRepsMin: number;
  targetRepsMax: number;
  restSeconds: number;
};

type PlanPreset = {
  name: string;
  description: string;
  equipment: string;
  goal: string;
  durationWeeks: number;
  days: { name: string; exercises: PresetExercise[] }[];
};

function movement(
  englishName: string,
  [targetRepsMin, targetRepsMax]: [number, number],
  targetSets = 2,
  restSeconds = 90,
): PresetExercise {
  return { englishName, targetSets, targetRepsMin, targetRepsMax, restSeconds };
}

export const planPresets: PlanPreset[] = [
  {
    name: "新手器械全身",
    description: "从腿举、下拉和绳索动作开始，熟悉健身房的基础器械。训练日之间留一天休息。",
    equipment: "固定器械、绳索",
    goal: "GENERAL_FITNESS",
    durationWeeks: 4,
    days: [
      {
        name: "全身 A",
        exercises: [
          movement("Leg Press", [8, 12]),
          movement("Lat Pulldown", [8, 12]),
          movement("Cable Fly", [10, 12]),
        ],
      },
      {
        name: "全身 B",
        exercises: [
          movement("Leg Press", [8, 12]),
          movement("Seated Cable Row", [8, 12]),
          movement("Leg Curl", [10, 12]),
        ],
      },
      {
        name: "全身 C",
        exercises: [
          movement("Leg Press", [8, 12]),
          movement("Lat Pulldown", [8, 12]),
          movement("Cable Fly", [10, 12]),
        ],
      },
    ],
  },
  {
    name: "居家徒手体能",
    description: "用俯卧撑、登山跑和转体练习基础体能。俯卧撑可从扶墙版本开始。",
    equipment: "无需器械",
    goal: "GENERAL_FITNESS",
    durationWeeks: 4,
    days: [
      {
        name: "推撑与核心",
        exercises: [
          movement("Push Up", [5, 10], 2, 60),
          movement("Mountain Climber", [10, 16], 2, 60),
          movement("Russian Twist", [8, 12], 2, 60),
        ],
      },
      {
        name: "协调与核心",
        exercises: [
          movement("Jumping Jack", [10, 16], 2, 60),
          movement("Push Up", [5, 10], 2, 60),
          movement("Mountain Climber", [10, 16], 2, 60),
        ],
      },
    ],
  },
  {
    name: "上肢／下肢分化",
    description: "上肢和下肢各练两次，可排在周一、周二、周四和周五。",
    equipment: "哑铃、固定器械、绳索",
    goal: "MUSCLE_GAIN",
    durationWeeks: 6,
    days: [
      {
        name: "上肢 A",
        exercises: [
          movement("Incline Dumbbell Press", [8, 12], 3),
          movement("Lat Pulldown", [8, 12], 3),
          movement("Dumbbell Lateral Raise", [10, 15]),
        ],
      },
      {
        name: "下肢 A",
        exercises: [
          movement("Leg Press", [8, 12], 3),
          movement("Leg Curl", [10, 15]),
          movement("Standing Calf Raise", [10, 15]),
        ],
      },
      {
        name: "上肢 B",
        exercises: [
          movement("Incline Dumbbell Press", [8, 12], 3),
          movement("Seated Cable Row", [8, 12], 3),
          movement("Triceps Pushdown", [10, 15]),
        ],
      },
      {
        name: "下肢 B",
        exercises: [
          movement("Leg Press", [8, 12], 3),
          movement("Leg Curl", [10, 15]),
          movement("Leg Extension", [10, 15]),
        ],
      },
    ],
  },
];

export async function loadPresetExercises(signal: AbortSignal) {
  const exercises = new Map<string, Exercise>();
  let page = 1;
  while (true) {
    const result = await api<PageResult<Exercise>>(
      `/exercises?size=100&page=${page}`,
      { signal },
    );
    for (const exercise of result.items) {
      if (exercise.englishName && !exercises.has(exercise.englishName)) {
        exercises.set(exercise.englishName, exercise);
      }
    }
    if (page >= result.pages) return exercises;
    page += 1;
  }
}

export function hasPresetExercises(
  preset: PlanPreset,
  exercises: ReadonlyMap<string, Exercise>,
) {
  return preset.days.every((day) =>
    day.exercises.every(({ englishName }) => exercises.has(englishName)),
  );
}

export function presetToDraft(
  preset: PlanPreset,
  exercises: ReadonlyMap<string, Exercise>,
): PlanDraft {
  return {
    name: preset.name,
    goal: preset.goal,
    description: preset.description,
    durationWeeks: preset.durationWeeks,
    days: preset.days.map((day, index) => ({
      ...newDay(index + 1),
      name: day.name,
      exercises: day.exercises.map(({ englishName, ...targets }) => {
        const exercise = exercises.get(englishName);
        if (!exercise) throw new Error(`预设动作不可用：${englishName}`);
        return { ...newExercise(exercise), ...targets, targetRpe: "" };
      }),
    })),
  };
}
