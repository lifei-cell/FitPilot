import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { renderWithQueryClient } from "../test/render";
import { server } from "../test/server";
import { ExercisesPage } from "./ExercisesPage";
import { NotificationsPage } from "./NotificationsPage";

describe("secondary product pages", () => {
  it("searches the exercise catalog and opens movement guidance", async () => {
    let keyword = "";
    server.use(
      http.get("/api/v1/exercises", ({ request }) => {
        keyword = new URL(request.url).searchParams.get("keyword") ?? "";
        return HttpResponse.json({ code: 0, message: "success", data: {
          items: keyword && !"杠铃卧推".includes(keyword) ? [] : [{ id: 1, name: "杠铃卧推",
            englishName: "Barbell Bench Press", category: "力量", equipment: "杠铃",
            primaryMuscle: "胸部", difficulty: "中级", description: "卧推说明", instructions: "肩胛稳定" },
          { id: 20, name: "站姿提踵", englishName: "Standing Calf Raise" }],
          total: 2, page: 1, size: 60, pages: 1,
        } });
      }),
    );
    const user = userEvent.setup();
    renderWithQueryClient(<ExercisesPage />);

    await user.click(await screen.findByRole("button", { name: /杠铃卧推/ }));
    expect(screen.getByText("肩胛稳定")).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "杠铃卧推动作示意图" })).toHaveAttribute("src", "/exercise-media/repdb/bench-press.webp");
    expect(screen.queryByTitle("杠铃卧推教学视频")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "观看教学视频" }));
    expect(screen.getByTitle("杠铃卧推教学视频")).toHaveAttribute("src", expect.stringContaining("player.bilibili.com/player.html?bvid=BV1JF411g7ua"));

    await user.click(screen.getByRole("button", { name: /站姿提踵/ }));
    expect(screen.queryByTitle("杠铃卧推教学视频")).not.toBeInTheDocument();
    expect(screen.queryByTitle("站姿提踵教学视频")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "在 B 站打开" })).toHaveAttribute("href", "https://www.bilibili.com/video/BV1zS4y1c77u/?p=38");
    await user.click(screen.getByRole("button", { name: "观看教学视频" }));
    expect(screen.getByTitle("站姿提踵教学视频")).toHaveAttribute("src", expect.stringContaining("&p=38"));
    await user.click(screen.getByRole("button", { name: "收起视频" }));
    expect(screen.queryByTitle("站姿提踵教学视频")).not.toBeInTheDocument();

    await user.type(screen.getByPlaceholderText("搜索卧推、深蹲…"), "不存在");
    expect(await screen.findByText("没有找到动作")).toBeInTheDocument();
    expect(keyword).toBe("不存在");
  });

  it("marks one notification and then all notifications as read", async () => {
    let items = [{ id: 1, type: "PERSONAL_RECORD", title: "新的个人纪录", message: "卧推 100kg", read: false,
      createdAt: "2026-09-02T08:00:00Z" }];
    let readAll = false;
    server.use(
      http.get("/api/v1/notifications", () =>
        HttpResponse.json({ code: 0, message: "success", data: items })),
      http.post("/api/v1/notifications/1/read", () => {
        items = items.map((item) => ({ ...item, read: true }));
        return HttpResponse.json({ code: 0, message: "success", data: null });
      }),
      http.post("/api/v1/notifications/read-all", () => {
        readAll = true;
        return HttpResponse.json({ code: 0, message: "success", data: null });
      }),
    );
    const user = userEvent.setup();
    renderWithQueryClient(<NotificationsPage />);

    await user.click(await screen.findByRole("button", { name: /新的个人纪录/ }));
    await waitFor(() => expect(screen.getByRole("button", { name: /新的个人纪录/ })).toHaveClass("read"));
    await user.click(screen.getByRole("button", { name: "全部已读" }));
    await waitFor(() => expect(readAll).toBe(true));
  });
});
