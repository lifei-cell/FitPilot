import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { CitationFeedback } from "./CitationFeedback";

describe("CitationFeedback", () => {
  it("submits citation-specific negative feedback", () => {
    const feedback = vi.fn();
    render(<CitationFeedback retrievalId="retrieval-1" citations={[{
      documentId: "doc-1", sourceUrl: "https://example.org/guide", sourceLicense: "CC-BY",
      publisher: "ACSM", trustLevel: "PROFESSIONAL", documentVersion: 3,
    }]} onFeedback={feedback} />);
    expect(screen.getByText("ACSM · PROFESSIONAL · v3")).toBeInTheDocument();
    fireEvent.click(screen.getByText("有帮助"));
    fireEvent.click(screen.getByText("没帮助"));
    fireEvent.click(screen.getByRole("button", { name: "引用有帮助" }));
    fireEvent.click(screen.getByRole("button", { name: "引用错误" }));
    expect(feedback).toHaveBeenCalledWith("ANSWER", "", "HELPFUL");
    expect(feedback).toHaveBeenCalledWith("ANSWER", "", "NOT_HELPFUL", "OTHER");
    expect(feedback).toHaveBeenCalledWith("CITATION", "doc-1", "HELPFUL");
    expect(feedback).toHaveBeenCalledWith("CITATION", "doc-1", "NOT_HELPFUL", "WRONG_CITATION");
  });

  it("shows a fallback label when publisher is absent", () => {
    render(<CitationFeedback retrievalId="retrieval-2" citations={[{
      documentId: "doc-2", sourceUrl: "https://example.org/community", sourceLicense: "CC-BY",
      trustLevel: "COMMUNITY", documentVersion: 1,
    }]} onFeedback={vi.fn()} />);
    expect(screen.getByText("来源 · COMMUNITY · v1")).toBeInTheDocument();
  });

  it("shows submission and error states", () => {
    const { rerender } = render(<CitationFeedback retrievalId="retrieval-3" citations={[]} busy submitted
      onFeedback={vi.fn()} />);
    expect(screen.getByText("反馈已提交，审核前不会影响在线排序。")).toBeInTheDocument();
    expect(screen.getByText("有帮助")).toBeDisabled();
    rerender(<CitationFeedback retrievalId="retrieval-3" citations={[]} error="网络异常" onFeedback={vi.fn()} />);
    expect(screen.getByRole("alert")).toHaveTextContent("网络异常");
  });
});
