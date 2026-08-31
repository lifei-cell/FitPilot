export type PageResult<T> = {
  items: T[];
  total: number;
  page: number;
  size: number;
  pages: number;
};
export type Exercise = {
  id: number;
  name: string;
  englishName?: string;
  category?: string;
  equipment?: string;
  difficulty?: string;
  primaryMuscle?: string;
  description?: string;
  instructions?: string;
};
export type PlanExercise = {
  id: number;
  exerciseId: number;
  exerciseName?: string;
  sequence: number;
  targetSets: number;
  targetRepsMin: number;
  targetRepsMax: number;
  targetRpe?: number;
  restSeconds?: number;
  notes?: string;
};
export type PlanDay = {
  id: number;
  dayNumber: number;
  name: string;
  notes?: string;
  exercises: PlanExercise[];
};
export type Plan = {
  id: number;
  name: string;
  description?: string;
  goal: string;
  durationWeeks: number;
  daysPerWeek: number;
  status: string;
  version: number;
  startedAt?: string;
  endedAt?: string;
  days: PlanDay[];
};
export type PlanSummary = Omit<Plan, "days" | "description" | "version"> & {
  startedAt?: string;
};
export type PlanExerciseInput = {
  exerciseId: number;
  sequence: number;
  targetSets: number;
  targetRepsMin: number;
  targetRepsMax: number;
  targetRpe?: number;
  restSeconds?: number;
  notes?: string;
};
export type PlanDayInput = {
  dayNumber: number;
  name: string;
  notes?: string;
  exercises: PlanExerciseInput[];
};
export type PlanCreateInput = {
  name: string;
  description?: string;
  goal: string;
  durationWeeks: number;
  days: PlanDayInput[];
};
export type PlanUpdateInput = PlanCreateInput & { version: number };
export type AgentPendingAction = {
  id: string;
  toolName: string;
  confirmationToken: string;
  expiresAt: string;
  preview: PlanCreateInput;
  guardrailWarnings: string[];
};
export type AgentSessionSummary = {
  id: string;
  title: string;
  status: "ACTIVE" | "ARCHIVED";
  lastMessageAt?: string;
  createdAt: string;
  updatedAt: string;
};
export type ConversationMessage = {
  id: number;
  role: "user" | "assistant" | "system";
  content: string;
  status: "COMPLETED" | "ERROR";
  executionId?: string;
  metadata: Record<string, unknown>;
  createdAt: string;
};
export type MessagePage = {
  items: ConversationMessage[];
  nextBeforeId?: number;
};
export type PendingActionSummary = {
  id: string;
  sessionId: string;
  toolName: string;
  status: string;
  expiresAt: string;
  preview: PlanCreateInput;
};
export type WorkoutSet = {
  id: number;
  setNumber: number;
  weightKg: number;
  reps: number;
  rpe?: number;
  rir?: number;
  isWarmup: boolean;
  isFailure: boolean;
  completedAt?: string;
};
export type WorkoutSetInput = {
  weightKg: number;
  reps: number;
  rpe?: number;
  rir?: number;
  isWarmup: boolean;
  isFailure: boolean;
};
export type WorkoutExercise = {
  id: number;
  exerciseId: number;
  exerciseName: string;
  sequence: number;
  targetSets?: number;
  targetRepsMin?: number;
  targetRepsMax?: number;
  targetRpe?: number;
  restSeconds?: number;
  notes?: string;
  sets: WorkoutSet[];
};
export type Workout = {
  id: number;
  name: string;
  status: string;
  startedAt: string;
  completedAt?: string;
  durationSeconds?: number;
  exercises: WorkoutExercise[];
};
export type WorkoutSummary = Pick<
  Workout,
  "id" | "name" | "status" | "startedAt" | "completedAt" | "durationSeconds"
>;
export type WorkoutFeedbackInput = {
  fatigueScore: number;
  painScore: number;
  notes?: string;
};
export type AdjustmentEvidence = {
  windowDays: number;
  completedWorkouts: number;
  planCompletionRate: number;
  setCompletionRate: number;
  averageRpe: number;
  feedbackCount: number;
  averageFatigue: number;
  latestPain: number;
  currentVolume: number;
  previousVolume: number;
  volumeChangeRate: number;
  personalRecords: number;
};
export type PlanAdjustment = {
  id: string;
  sourcePlanId: number;
  sourcePlanVersion: number;
  rule: string;
  status: string;
  evidence: AdjustmentEvidence;
  reasons: string[];
  proposal?: PlanCreateInput;
  pendingActionId?: string;
  draftPlanId?: number;
  createdAt: string;
};
