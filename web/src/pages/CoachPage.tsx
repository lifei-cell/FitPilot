import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { Bot, Send, ShieldCheck } from "lucide-react";
import { clearPendingAction, storePendingAction } from "../agent/pendingActionStorage";
import { clearAgentSession, loadAgentSession, storeAgentSession } from "../agent/sessionStorage";
import { accessTokenSubject, api, ApiError } from "../api/client";
import type {
  AgentPendingAction,
  AgentSessionSummary,
  ConversationMessage,
  MessagePage,
  PageResult,
  PendingActionSummary,
  Plan,
  PlanAdjustment,
  RagCitation,
} from "../api/types";
import { PageHeader, Panel } from "../components/PageParts";
import { ConversationSidebar } from "../features/agent/ConversationSidebar";
import { PendingPlanCard } from "../features/agent/PendingPlanCard";
import { AdjustmentInsightCard } from "../features/agent/AdjustmentInsightCard";
import { CitationFeedback } from "../features/agent/CitationFeedback";

type Reply = {
  answer: string;
  selectedTools: string[];
  degraded: boolean;
  confirmationRequired: boolean;
  pendingAction?: AgentPendingAction;
  retrievalId?: string;
  citations?: RagCitation[];
};
type Chat = { id?: number; role: "user" | "assistant" | "system"; text: string; tools?: string[]; error?: boolean;
  retrievalId?: string; citations?: RagCitation[] };

const welcome: Chat = {
  role: "assistant",
  text: "你好，我是 FitPilot 教练。告诉我你的目标、训练经验或今天的状态。",
};

export function CoachPage() {
  const client = useQueryClient();
  const userId = accessTokenSubject();
  const [session, setSession] = useState(() => loadAgentSession(userId) ?? "");
  const [chats, setChats] = useState<Chat[]>([welcome]);
  const [nextBeforeId, setNextBeforeId] = useState<number>();
  const [pending, setPending] = useState<AgentPendingAction | null>(null);
  const [pendingStatus, setPendingStatus] = useState<"pending" | "confirming" | "expired" | "error">("pending");
  const [pendingError, setPendingError] = useState("");
  const sessionRef = useRef(session);

  const sessions = useQuery({
    queryKey: ["agent-sessions", userId],
    queryFn: () => api<PageResult<AgentSessionSummary>>("/agent/sessions?size=100"),
    enabled: Boolean(userId),
  });
  const adjustments = useQuery({
    queryKey: ["plan-adjustments", userId],
    queryFn: () => api<PageResult<PlanAdjustment>>("/agent/plan-adjustments?size=5"),
    enabled: Boolean(userId),
  });

  const restorePending = useCallback(async (sessionId: string) => {
    const actions = await api<PendingActionSummary[]>(`/agent/sessions/${sessionId}/pending-actions`);
    const action = actions[0];
    if (!action) {
      setPending(null);
      clearPendingAction(userId);
      return;
    }
    const token = await api<{ confirmationToken: string; expiresAt: string }>(
      `/agent/pending-actions/${action.id}/confirmation-token`, { method: "POST" },
    );
    const restored: AgentPendingAction = {
      id: action.id,
      toolName: action.toolName,
      confirmationToken: token.confirmationToken,
      expiresAt: token.expiresAt,
      preview: action.preview,
      guardrailWarnings: [],
    };
    setPending(restored);
    setPendingStatus("pending");
    storePendingAction(userId, restored);
  }, [userId]);

  const selectSession = useCallback(async (id: string) => {
    sessionRef.current = id;
    setSession(id);
    storeAgentSession(userId, id);
    const history = await api<MessagePage>(`/agent/sessions/${id}/history?limit=50`);
    setChats(history.items.length ? history.items.map(toChat) : [welcome]);
    setNextBeforeId(history.nextBeforeId);
    await restorePending(id);
  }, [restorePending, userId]);

  useEffect(() => {
    const restored = loadAgentSession(userId);
    if (restored) {
      void selectSession(restored).catch(() => {
        clearAgentSession(userId);
        sessionRef.current = "";
        setSession("");
        setChats([welcome]);
      });
    } else {
      sessionRef.current = "";
      setSession("");
      setChats([welcome]);
    }
  }, [selectSession, userId]);

  const createSession = useMutation({
    mutationFn: () => api<{ id: string }>("/agent/sessions", { method: "POST" }),
    onSuccess: async ({ id }) => {
      await client.invalidateQueries({ queryKey: ["agent-sessions"] });
      await selectSession(id);
    },
  });

  const updateSession = useMutation({
    mutationFn: ({ id, title, status }: { id: string; title?: string; status?: "ACTIVE" | "ARCHIVED" }) =>
      api<void>(`/agent/sessions/${id}`, { method: "PATCH", body: JSON.stringify({ title, status }) }),
    onSuccess: async (_, variables) => {
      await client.invalidateQueries({ queryKey: ["agent-sessions"] });
      if (variables.status === "ARCHIVED" && variables.id === sessionRef.current) resetConversation();
    },
  });

  const deleteSession = useMutation({
    mutationFn: (id: string) => api<void>(`/agent/sessions/${id}`, { method: "DELETE" }),
    onSuccess: async (_, id) => {
      await client.invalidateQueries({ queryKey: ["agent-sessions"] });
      if (id === sessionRef.current) resetConversation();
    },
  });

  const send = useMutation({
    mutationFn: async (message: string) => {
      let id = sessionRef.current;
      if (!id) {
        id = (await api<{ id: string }>("/agent/sessions", { method: "POST" })).id;
        sessionRef.current = id;
        setSession(id);
        storeAgentSession(userId, id);
      }
      return api<Reply>(`/agent/sessions/${id}/messages`, {
        method: "POST",
        body: JSON.stringify({ message }),
      });
    },
    onSuccess: (reply) => {
      setChats((current) => [...current, { role: "assistant", text: reply.answer, tools: reply.selectedTools,
        retrievalId: reply.retrievalId, citations: reply.citations }]);
      if (reply.pendingAction) {
        setPending(reply.pendingAction);
        setPendingStatus("pending");
        setPendingError("");
        storePendingAction(userId, reply.pendingAction);
      }
      void client.invalidateQueries({ queryKey: ["agent-sessions"] });
      void client.invalidateQueries({ queryKey: ["plan-adjustments"] });
    },
  });

  const confirm = useMutation({
    mutationFn: (action: AgentPendingAction) => api<Plan>(`/agent/pending-actions/${action.id}/confirm`, {
      method: "POST",
      body: JSON.stringify({ confirmationToken: action.confirmationToken }),
    }),
    onMutate: () => { setPendingStatus("confirming"); setPendingError(""); },
    onSuccess: (plan) => {
      clearPendingAction(userId);
      setPending(null);
      setPendingStatus("pending");
      setChats((current) => [...current, { role: "assistant", text: `计划“${plan.name}”已确认并保存为草稿。` }]);
      void client.invalidateQueries({ queryKey: ["plans"] });
      void client.invalidateQueries({ queryKey: ["plan-adjustments"] });
    },
    onError: (error) => handleConfirmationError(error),
  });
  const rejectAdjustment = useMutation({
    mutationFn: (id: string) => api<void>(`/agent/plan-adjustments/${id}/reject`, { method: "POST" }),
    onSuccess: async () => {
      clearPendingAction(userId);
      setPending(null);
      await client.invalidateQueries({ queryKey: ["plan-adjustments"] });
    },
  });
  const submitRagFeedback = useMutation({
    mutationFn: ({ retrievalId, targetType, targetKey, rating, reason }: {
      retrievalId: string; targetType: "ANSWER" | "CITATION"; targetKey: string;
      rating: "HELPFUL" | "NOT_HELPFUL"; reason?: string;
    }) => api(`/rag/retrievals/${retrievalId}/feedback`, { method: "PUT",
      body: JSON.stringify({ targetType, targetKey, rating, reason }) }),
  });

  async function loadOlder() {
    if (!sessionRef.current || !nextBeforeId) return;
    const history = await api<MessagePage>(`/agent/sessions/${sessionRef.current}/history?limit=50&beforeId=${nextBeforeId}`);
    setChats((current) => [...history.items.map(toChat), ...current]);
    setNextBeforeId(history.nextBeforeId);
  }

  function resetConversation() {
    clearAgentSession(userId);
    clearPendingAction(userId);
    sessionRef.current = "";
    setSession("");
    setChats([welcome]);
    setPending(null);
    setNextBeforeId(undefined);
  }

  function rename(item: AgentSessionSummary) {
    const title = window.prompt("新的会话名称", item.title)?.trim();
    if (title) updateSession.mutate({ id: item.id, title });
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const input = event.currentTarget.elements.namedItem("message") as HTMLInputElement;
    const value = input.value.trim();
    if (!value) return;
    setChats((current) => [...current, { role: "user", text: value }]);
    send.mutate(value);
    input.value = "";
  }

  function handleConfirmationError(error: Error) {
    if (error instanceof ApiError && error.code === 80003) {
      clearPendingAction(userId); setPendingStatus("expired"); setPendingError("确认令牌已过期，请重新签发或生成计划。"); return;
    }
    if (error instanceof ApiError && error.code === 80005) {
      clearPendingAction(userId); setPending(null); setPendingStatus("pending");
      setChats((current) => [...current, { role: "assistant", text: "该计划已经处理，不能重复确认。" }]); return;
    }
    setPendingStatus("error"); setPendingError(error.message);
  }

  return (
    <main className="page coach-page">
      <PageHeader eyebrow="AI TRAINING PARTNER" title="AI 教练" description="跨设备保留训练咨询，高风险操作仍需确认。"
        action={<span className="date-chip"><ShieldCheck size={16} />高风险操作需确认</span>} />
      <div className="coach-workspace">
        <ConversationSidebar sessions={sessions.data?.items ?? []} selected={session} busy={createSession.isPending}
          onCreate={() => createSession.mutate()} onSelect={(id) => void selectSession(id)} onRename={rename}
          onArchive={(item) => updateSession.mutate({ id: item.id, status: "ARCHIVED" })}
          onDelete={(item) => { if (window.confirm(`删除会话“${item.title}”？`)) deleteSession.mutate(item.id); }} />
        <div className="coach-chat-column">
          {adjustments.data?.items[0] ? <AdjustmentInsightCard adjustment={adjustments.data.items[0]}
            onReject={() => rejectAdjustment.mutate(adjustments.data!.items[0].id)} /> : null}
        <Panel className="chat-panel">
          <div className="chat-log">
            {nextBeforeId ? <button className="text-button history-button" onClick={() => void loadOlder()}>加载更早消息</button> : null}
            {chats.map((chat, index) => (
              <div className={`chat-message ${chat.role} ${chat.error ? "error" : ""}`} key={chat.id ?? index}>
                {chat.role !== "user" && <span className="bot-avatar"><Bot size={18} /></span>}
                <div><p>{chat.text}</p>{chat.tools?.length ? <small>已使用：{chat.tools.join(" · ")}</small> : null}
                  {chat.retrievalId ? <CitationFeedback retrievalId={chat.retrievalId}
                    citations={chat.citations ?? []}
                    busy={submitRagFeedback.isPending && submitRagFeedback.variables?.retrievalId === chat.retrievalId}
                    submitted={submitRagFeedback.isSuccess && submitRagFeedback.variables?.retrievalId === chat.retrievalId}
                    error={submitRagFeedback.isError && submitRagFeedback.variables?.retrievalId === chat.retrievalId
                      ? submitRagFeedback.error.message : undefined}
                    onFeedback={(targetType, targetKey, rating, reason) =>
                      submitRagFeedback.mutate({ retrievalId: chat.retrievalId!, targetType, targetKey, rating, reason })} /> : null}
                </div>
              </div>
            ))}
            {send.isPending ? <div className="chat-message assistant"><span className="bot-avatar"><Bot size={18} /></span><div><p>正在分析训练上下文…</p></div></div> : null}
            {pending ? <PendingPlanCard action={pending} status={pendingStatus} error={pendingError}
              onConfirm={() => confirm.mutate(pending)} onExpire={() => { clearPendingAction(userId); setPendingStatus("expired"); }} /> : null}
          </div>
          <form className="coach-input" onSubmit={submit}>
            <input name="message" maxLength={4000} placeholder="例如：根据最近训练量，今天深蹲该如何安排？" />
            <button className="primary-button" disabled={send.isPending || confirm.isPending}><Send size={17} /></button>
          </form>
          {send.error ? <p className="form-error">{send.error.message}</p> : null}
        </Panel></div>
      </div>
    </main>
  );
}

function toChat(message: ConversationMessage): Chat {
  return { id: message.id, role: message.role, text: message.content, error: message.status === "ERROR",
    retrievalId: typeof message.metadata.retrievalId === "string" ? message.metadata.retrievalId : undefined,
    citations: Array.isArray(message.metadata.citations) ? message.metadata.citations as RagCitation[] : undefined };
}
