import { expect, test, type Page } from "@playwright/test";

const token = "header.eyJzdWIiOiI5OSJ9.signature";
const sessionId = "11111111-1111-4111-8111-111111111111";
const retrievalId = "22222222-2222-4222-8222-222222222222";
const documentId = "33333333-3333-4333-8333-333333333333";

async function authenticated(page: Page, currentSession = false) {
  await page.addInitScript(({ token, currentSession, sessionId }) => {
    sessionStorage.setItem("fitpilot_access", token);
    if (currentSession) sessionStorage.setItem("fitpilot_agent_session:99", sessionId);
  }, { token, currentSession, sessionId });
}

test("两个浏览器上下文恢复同一 Agent 会话并反馈引用", async ({ browser }) => {
  let feedbackBody: Record<string, string> | undefined;
  const contextA = await browser.newContext();
  const contextB = await browser.newContext();
  const pageA = await contextA.newPage();
  const pageB = await contextB.newPage();
  await authenticated(pageA, true);
  await authenticated(pageB, false);

  for (const page of [pageA, pageB]) {
    await page.route("**/api/v1/**", async (route) => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      const ok = (data: unknown) => route.fulfill({ json: { code: 0, message: "success", data } });
      if (path === "/api/v1/agent/sessions") return ok({ items: [{ id: sessionId, title: "跨设备训练咨询",
        status: "ACTIVE", lastMessageAt: "2026-09-01T08:00:00Z", createdAt: "2026-09-01T07:00:00",
        updatedAt: "2026-09-01T08:00:00" }], total: 1, page: 1, size: 100, pages: 1 });
      if (path.endsWith(`/agent/sessions/${sessionId}/history`)) return ok({ items: [{ id: 1, role: "assistant",
        content: "RPE 8 通常保留约 2 次重复。", status: "COMPLETED", metadata: { retrievalId, citations: [{
          documentId, sourceUrl: "https://example.org/rpe", sourceLicense: "CC-BY", publisher: "FitPilot Research",
          trustLevel: "PROFESSIONAL", documentVersion: 2 }] }, createdAt: "2026-09-01T08:00:00Z" }] });
      if (path.endsWith(`/agent/sessions/${sessionId}/pending-actions`)) return ok([]);
      if (path === "/api/v1/agent/plan-adjustments") return ok({ items: [], total: 0, page: 1, size: 5, pages: 0 });
      if (path === `/api/v1/rag/retrievals/${retrievalId}/feedback`) {
        feedbackBody = request.postDataJSON() as Record<string, string>;
        return ok({ id: "feedback-1", reviewStatus: "PENDING" });
      }
      return route.fulfill({ status: 404, json: { code: 404, message: "unmocked", data: null } });
    });
  }

  await pageA.goto("/coach");
  await expect(pageA.getByText("RPE 8 通常保留约 2 次重复。")).toBeVisible();
  await pageB.goto("/coach");
  await pageB.getByRole("button", { name: "跨设备训练咨询" }).click();
  await expect(pageB.getByText("RPE 8 通常保留约 2 次重复。")).toBeVisible();
  await pageB.getByRole("button", { name: "引用错误" }).click();
  await expect.poll(() => feedbackBody).toMatchObject({ targetType: "CITATION", targetKey: documentId,
    rating: "NOT_HELPFUL", reason: "WRONG_CITATION" });

  await contextA.close();
  await contextB.close();
});

test("完成 Workout 时提交反馈并显示调整入口", async ({ page }) => {
  let completionBody: { feedback?: { fatigueScore: number; painScore: number; notes?: string } } | undefined;
  await authenticated(page);
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const ok = (data: unknown) => route.fulfill({ json: { code: 0, message: "success", data } });
    if (url.pathname === "/api/v1/workouts/active/current") return ok({ id: 7, name: "下肢训练", status: "IN_PROGRESS",
      startedAt: "2026-09-01T07:00:00", exercises: [] });
    if (url.pathname === "/api/v1/training-plans/active/current") return ok({ id: 1, name: "力量计划", days: [] });
    if (url.pathname === "/api/v1/workouts") return ok({ items: [], total: 0, page: 1, size: 10, pages: 0 });
    if (url.pathname === "/api/v1/workouts/7/complete") {
      completionBody = request.postDataJSON() as typeof completionBody;
      return ok({ workoutId: 7, durationSeconds: 3600, personalRecords: [] });
    }
    return route.fulfill({ status: 404, json: { code: 404, message: "unmocked", data: null } });
  });

  await page.goto("/workouts");
  await page.getByRole("button", { name: "完成训练" }).click();
  await page.getByLabel("疲劳").fill("8");
  await page.getByLabel("疼痛").fill("2");
  await page.getByPlaceholder("可选：记录不适位置、恢复状态或其他感受").fill("睡眠不足");
  await page.getByRole("button", { name: "提交并完成" }).click();
  await expect(page.getByText("训练已记录")).toBeVisible();
  expect(completionBody?.feedback).toEqual({ fatigueScore: 8, painScore: 2, notes: "睡眠不足" });
});
