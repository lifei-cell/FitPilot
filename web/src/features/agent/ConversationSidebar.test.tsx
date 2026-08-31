import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ConversationSidebar } from "./ConversationSidebar";

const session = {
  id: "8af2dbb0-4c39-4f3d-9e17-82966e51a07c",
  title: "深蹲训练建议",
  status: "ACTIVE" as const,
  createdAt: "2026-08-31T10:00:00",
  updatedAt: "2026-08-31T10:01:00",
};

describe("ConversationSidebar", () => {
  it("creates, selects and manages an owned conversation", async () => {
    const callbacks = {
      onCreate: vi.fn(), onSelect: vi.fn(), onRename: vi.fn(), onArchive: vi.fn(), onDelete: vi.fn(),
    };
    const user = userEvent.setup();
    render(<ConversationSidebar sessions={[session]} selected={session.id} busy={false} {...callbacks} />);

    await user.click(screen.getByRole("button", { name: "新对话" }));
    await user.click(screen.getByRole("button", { name: "深蹲训练建议" }));
    await user.click(screen.getByRole("button", { name: "重命名" }));
    await user.click(screen.getByRole("button", { name: "归档" }));
    await user.click(screen.getByRole("button", { name: "删除" }));

    expect(callbacks.onCreate).toHaveBeenCalledOnce();
    expect(callbacks.onSelect).toHaveBeenCalledWith(session.id);
    expect(callbacks.onRename).toHaveBeenCalledWith(session);
    expect(callbacks.onArchive).toHaveBeenCalledWith(session);
    expect(callbacks.onDelete).toHaveBeenCalledWith(session);
  });
});
