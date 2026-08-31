import { Archive, MessageSquarePlus, Pencil, Trash2 } from "lucide-react";
import type { AgentSessionSummary } from "../../api/types";

type Props = {
  sessions: AgentSessionSummary[];
  selected?: string;
  busy: boolean;
  onCreate(): void;
  onSelect(id: string): void;
  onRename(session: AgentSessionSummary): void;
  onArchive(session: AgentSessionSummary): void;
  onDelete(session: AgentSessionSummary): void;
};

export function ConversationSidebar({ sessions, selected, busy, onCreate, onSelect, onRename, onArchive, onDelete }: Props) {
  return (
    <aside className="conversation-sidebar">
      <button className="conversation-new" disabled={busy} onClick={onCreate}>
        <MessageSquarePlus size={16} /> 新对话
      </button>
      <div className="conversation-list">
        {sessions.map((session) => (
          <div className={`conversation-item ${selected === session.id ? "active" : ""}`} key={session.id}>
            <button className="conversation-title" onClick={() => onSelect(session.id)}>{session.title}</button>
            <div className="conversation-actions">
              <button aria-label="重命名" onClick={() => onRename(session)}><Pencil size={13} /></button>
              <button aria-label="归档" onClick={() => onArchive(session)}><Archive size={13} /></button>
              <button aria-label="删除" onClick={() => onDelete(session)}><Trash2 size={13} /></button>
            </div>
          </div>
        ))}
        {sessions.length === 0 ? <p>还没有历史对话</p> : null}
      </div>
    </aside>
  );
}
