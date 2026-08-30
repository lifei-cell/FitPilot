import { useMutation, useQueryClient } from "@tanstack/react-query";
import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
} from "react";
import { Bot, Send, ShieldCheck } from "lucide-react";
import {
  clearPendingAction,
  loadPendingAction,
  storePendingAction,
} from "../agent/pendingActionStorage";
import { clearAgentSession, loadAgentSession, storeAgentSession } from "../agent/sessionStorage";
import { accessTokenSubject, api, ApiError } from "../api/client";
import type { AgentPendingAction, Plan } from "../api/types";
import { PageHeader, Panel } from "../components/PageParts";
import { PendingPlanCard } from "../features/agent/PendingPlanCard";

type Reply = {
  answer: string;
  selectedTools: string[];
  degraded: boolean;
  confirmationRequired: boolean;
  pendingAction?: AgentPendingAction;
};
type Chat = { role: "user" | "assistant"; text: string; tools?: string[] };
type StoredMessage = { role: "user" | "assistant"; content: string };

const welcome: Chat = {
  role: "assistant",
  text: "你好，我是 FitPilot 教练。告诉我你的目标、训练经验或今天的状态。",
};

export function CoachPage() {
  const client = useQueryClient();
  const userId = accessTokenSubject();
  const [session, setSession] = useState(() => loadAgentSession(userId) ?? "");
  const [chats, setChats] = useState<Chat[]>([welcome]);
  const [pending, setPending] = useState<AgentPendingAction | null>(() =>
    loadPendingAction(userId),
  );
  const [pendingStatus, setPendingStatus] = useState<
    "pending" | "confirming" | "expired" | "error"
  >("pending");
  const [pendingError, setPendingError] = useState("");
  const sessionRef = useRef(session);

  useEffect(() => {
    setPending(loadPendingAction(userId));
    setPendingStatus("pending");
    setPendingError("");
  }, [userId]);

  useEffect(() => {
    sessionRef.current = session;
  }, [session]);

  useEffect(() => {
    const restoredSession = loadAgentSession(userId);
    sessionRef.current = restoredSession ?? "";
    setSession(restoredSession ?? "");
    if (!restoredSession) {
      setChats([welcome]);
      return;
    }

    let cancelled = false;
    void api<StoredMessage[]>(`/agent/sessions/${restoredSession}/messages`)
      .then((messages) => {
        if (cancelled) return;
        if (messages.length === 0) {
          clearAgentSession(userId);
          sessionRef.current = "";
          setSession("");
          setChats([welcome]);
          return;
        }
        setChats(messages.map((message) => ({ role: message.role, text: message.content })));
      })
      .catch(() => {
        if (cancelled) return;
        clearAgentSession(userId);
        sessionRef.current = "";
        setSession("");
        setChats([welcome]);
      });
    return () => {
      cancelled = true;
    };
  }, [userId]);

  const send = useMutation({
    mutationFn: async (message: string) => {
      let id = sessionRef.current;
      if (!id) {
        id = (await api<{ id: string }>("/agent/sessions", { method: "POST" }))
          .id;
        sessionRef.current = id;
        setSession(id);
        storeAgentSession(userId, id);
      }
      return api<Reply>(`/agent/sessions/${id}/messages`, {
        method: "POST",
        body: JSON.stringify({ message }),
      });
    },
    onSuccess: (r) => {
      setChats((c) => [
        ...c,
        { role: "assistant", text: r.answer, tools: r.selectedTools },
      ]);
      if (r.pendingAction) {
        setPending(r.pendingAction);
        setPendingStatus("pending");
        setPendingError("");
        storePendingAction(userId, r.pendingAction);
      }
    },
  });
  const confirm = useMutation({
    mutationFn: (action: AgentPendingAction) =>
      api<Plan>(`/agent/pending-actions/${action.id}/confirm`, {
        method: "POST",
        body: JSON.stringify({
          confirmationToken: action.confirmationToken,
        }),
      }),
    onMutate: () => {
      setPendingStatus("confirming");
      setPendingError("");
    },
    onSuccess: (plan) => {
      clearPendingAction(userId);
      setPending(null);
      setPendingStatus("pending");
      setChats((current) => [
        ...current,
        {
          role: "assistant",
          text: `计划“${plan.name}”已确认并保存为草稿，你可以前往训练计划页面检查或激活。`,
        },
      ]);
      void client.invalidateQueries({ queryKey: ["plans"] });
    },
    onError: (error) => {
      if (error instanceof ApiError && error.code === 80003) {
        clearPendingAction(userId);
        setPendingStatus("expired");
        setPendingError("确认令牌已过期或失效，请重新生成计划。");
        return;
      }
      if (error instanceof ApiError && error.code === 80005) {
        clearPendingAction(userId);
        setPending(null);
        setPendingStatus("pending");
        setPendingError("");
        setChats((current) => [
          ...current,
          { role: "assistant", text: "该计划已经处理，不能重复确认。" },
        ]);
        return;
      }
      setPendingStatus("error");
      setPendingError(error.message);
    },
  });
  const expirePending = useCallback(() => {
    clearPendingAction(userId);
    setPendingStatus("expired");
    setPendingError("");
  }, [userId]);
  function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const input = e.currentTarget.elements.namedItem(
      "message",
    ) as HTMLInputElement;
    const value = input.value.trim();
    if (!value) return;
    setChats((c) => [...c, { role: "user", text: value }]);
    send.mutate(value);
    input.value = "";
  }
  return (
    <main className="page coach-page">
      <PageHeader
        eyebrow="AI TRAINING PARTNER"
        title="AI 教练"
        description="基于你的计划、训练数据和知识库给出可执行建议。"
        action={
          <span className="date-chip">
            <ShieldCheck size={16} />
            高风险操作需确认
          </span>
        }
      />
      <Panel className="chat-panel">
        <div className="chat-log">
          {chats.map((chat, index) => (
            <div className={`chat-message ${chat.role}`} key={index}>
              {chat.role === "assistant" && (
                <span className="bot-avatar">
                  <Bot size={18} />
                </span>
              )}
              <div>
                <p>{chat.text}</p>
                {chat.tools?.length ? (
                  <small>已使用：{chat.tools.join(" · ")}</small>
                ) : null}
              </div>
            </div>
          ))}
          {send.isPending && (
            <div className="chat-message assistant">
              <span className="bot-avatar">
                <Bot size={18} />
              </span>
              <div>
                <p>正在分析你的训练上下文…</p>
              </div>
            </div>
          )}
          {pending && (
            <PendingPlanCard
              action={pending}
              status={pendingStatus}
              error={pendingError}
              onConfirm={() => confirm.mutate(pending)}
              onExpire={expirePending}
            />
          )}
        </div>
        <form className="coach-input" onSubmit={submit}>
          <input
            name="message"
            maxLength={4000}
            placeholder="例如：根据我最近的训练量，今天深蹲该如何安排？"
          />
          <button
            className="primary-button"
            disabled={send.isPending || confirm.isPending}
          >
            <Send size={17} />
          </button>
        </form>
        {send.error && <p className="form-error">{send.error.message}</p>}
      </Panel>
    </main>
  );
}
