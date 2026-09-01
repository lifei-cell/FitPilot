import type { RagCitation } from "../../api/types";

type Props = {
  retrievalId: string;
  citations: RagCitation[];
  busy?: boolean;
  error?: string;
  submitted?: boolean;
  onFeedback: (targetType: "ANSWER" | "CITATION", targetKey: string,
    rating: "HELPFUL" | "NOT_HELPFUL", reason?: string) => void;
};

export function CitationFeedback({ retrievalId, citations, busy = false, error, submitted = false, onFeedback }: Props) {
  return <div className="citation-feedback" data-retrieval-id={retrievalId}>
    <div className="answer-feedback"><span>这条回答有帮助吗？</span>
      <button disabled={busy} onClick={() => onFeedback("ANSWER", "", "HELPFUL")}>有帮助</button>
      <button disabled={busy} onClick={() => onFeedback("ANSWER", "", "NOT_HELPFUL", "OTHER")}>没帮助</button>
    </div>
    {citations.map((citation) => <div className="citation-item" key={citation.documentId}>
      <a href={citation.sourceUrl} target="_blank" rel="noreferrer">
        {citation.publisher || "来源"} · {citation.trustLevel} · v{citation.documentVersion}
      </a>
      <button disabled={busy} aria-label="引用有帮助" onClick={() => onFeedback("CITATION", citation.documentId, "HELPFUL")}>✓</button>
      <button disabled={busy} aria-label="引用错误" onClick={() => onFeedback("CITATION", citation.documentId, "NOT_HELPFUL", "WRONG_CITATION")}>×</button>
    </div>)}
    {submitted ? <small>反馈已提交，审核前不会影响在线排序。</small> : null}
    {error ? <small role="alert">反馈提交失败：{error}</small> : null}
  </div>;
}
