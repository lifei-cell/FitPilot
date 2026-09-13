import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "../../test/server";
import { storeToken } from "../../api/client";
import type { Workout } from "../../api/types";
import { enqueue, operations, readDraft, saveDraft, saveSnapshot, snapshot, withPending } from "./workoutRecoveryStore";
import { syncWorkoutQueue } from "./syncWorkoutQueue";

const workout: Workout = { id: 7, name: "卧推", status: "IN_PROGRESS", startedAt: "2026-09-12T08:00:00Z",
  exercises: [{ id: 71, exerciseId: 1, exerciseName: "卧推", sequence: 1, sets: [] }] };
const payload = { weightKg: 60, reps: 10, isWarmup: false, isFailure: false };
function login() { storeToken("header.eyJzdWIiOiI5OSJ9.signature"); }

describe("workout recovery", () => {
  it("restores drafts and immutable queued sets within the original account only", () => {
    saveSnapshot("99", workout);
    const id = crypto.randomUUID();
    saveDraft("99", 7, 71, { id, weight: "62.5", reps: "", rpe: "" });
    expect(readDraft("99", 7, 71)?.weight).toBe("62.5");
    expect(snapshot("other")).toBeNull();
    expect(readDraft("other", 7, 71)).toBeNull();
    enqueue("99", 7, 71, id, payload);
    enqueue("99", 7, 71, id, payload);
    expect(operations("99")).toHaveLength(1);
    expect(readDraft("99", 7, 71)).toBeNull();
    expect(withPending(snapshot("99")!, operations("99")).exercises[0].sets[0].localOperationId).toBe(id);
    const confirmed = { ...workout, exercises: [{ ...workout.exercises[0],
      sets: [{ ...payload, id: 701, setNumber: 1, clientOperationId: id }] }] };
    expect(withPending(confirmed, operations("99")).exercises[0].sets).toHaveLength(1);
    expect(() => enqueue("99", 7, 71, id, { ...payload, reps: 8 })).toThrow(/核对/);
  });

  it("does not claim local success when storage is full", () => {
    const write = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("quota"); });
    try { expect(() => enqueue("99", 7, 71, crypto.randomUUID(), payload)).toThrow(/保存失败/); }
    finally { write.mockRestore(); }
    expect(operations("99")).toHaveLength(0);
  });

  it("replays a lost acknowledgement with the same operation id and reconciles server truth", async () => {
    login();
    saveSnapshot("99", workout);
    const id = crypto.randomUUID();
    enqueue("99", 7, 71, id, payload);
    const ids: string[] = [];
    server.use(
      http.post("/api/v1/workouts/7/exercises/71/sets", ({ request }) => {
        ids.push(request.headers.get("Client-Operation-Id")!);
        return ids.length === 1 ? HttpResponse.error() : HttpResponse.json({ code: 0, data: { id: 701 } });
      }),
      http.get("/api/v1/workouts/7", () => HttpResponse.json({ code: 0, data: { ...workout,
        exercises: [{ ...workout.exercises[0], sets: [{ ...payload, id: 701, setNumber: 1 }] }],
      } })),
    );
    await expect(syncWorkoutQueue("99")).rejects.toThrow();
    expect(operations("99")).toHaveLength(1);
    await syncWorkoutQueue("99");
    expect(ids).toEqual([id, id]);
    expect(operations("99")).toHaveLength(0);
    expect(snapshot("99")?.exercises[0].sets).toHaveLength(1);
  });

  it("keeps conflicting records and never sends them under another account", async () => {
    login();
    enqueue("99", 7, 71, crypto.randomUUID(), payload);
    let requests = 0;
    server.use(http.post("/api/v1/workouts/7/exercises/71/sets", () => {
      requests++;
      return HttpResponse.json({ code: 30003, message: "训练已结束" }, { status: 409 });
    }));
    await expect(syncWorkoutQueue("99")).rejects.toThrow(/同步暂停/);
    expect(operations("99")).toHaveLength(1);
    storeToken("header.eyJzdWIiOiIxMDAifQ.signature");
    await syncWorkoutQueue("99");
    expect(requests).toBe(1);
  });
});
