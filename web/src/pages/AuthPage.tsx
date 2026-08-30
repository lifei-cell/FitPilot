import { useState, type FormEvent } from "react";
import { ArrowRight, CheckCircle2, Dumbbell } from "lucide-react";
import { Brand } from "../components/Brand";
import { useAuth } from "../auth/AuthContext";

export function AuthPage() {
  const auth = useAuth();
  const [mode, setMode] = useState<"login" | "register">("login");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError("");
    const data = new FormData(event.currentTarget);
    try {
      const username = String(data.get("username"));
      const password = String(data.get("password"));
      if (mode === "register")
        await auth.register(username, String(data.get("email")), password);
      else await auth.login(username, password);
      history.replaceState({}, "", "/");
      window.dispatchEvent(new PopStateEvent("popstate"));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "登录失败");
    } finally {
      setBusy(false);
    }
  }
  return (
    <main className="auth-page">
      <section className="auth-story">
        <Brand />
        <div className="auth-copy">
          <p className="eyebrow">STRENGTH, STRUCTURED.</p>
          <h1>
            训练不是打卡，
            <br />
            <em>是持续变强。</em>
          </h1>
          <p>计划、训练记录、个人纪录与 AI 教练，汇聚在同一条成长路径上。</p>
          <div className="proof-row">
            <span>
              <strong>01</strong>科学计划
            </span>
            <span>
              <strong>02</strong>实时记录
            </span>
            <span>
              <strong>03</strong>数据反馈
            </span>
          </div>
        </div>
        <div className="auth-visual" aria-hidden="true">
          <Dumbbell size={100} />
          <span>BUILD / TRACK / EVOLVE</span>
        </div>
      </section>
      <section className="auth-panel">
        <div className="auth-form-wrap">
          <p className="eyebrow">
            {mode === "login" ? "WELCOME BACK" : "START YOUR JOURNEY"}
          </p>
          <h2>{mode === "login" ? "继续今天的训练" : "建立你的训练档案"}</h2>
          <p className="subtle">
            {mode === "login"
              ? "登录 FitPilot，查看下一组该做什么。"
              : "30 秒完成注册，即刻开始规划。"}
          </p>
          <form onSubmit={submit} className="auth-form">
            <label>
              用户名
              <input
                name="username"
                minLength={3}
                required
                autoComplete="username"
                placeholder="fitpilot_user"
              />
            </label>
            {mode === "register" && (
              <label>
                邮箱
                <input
                  name="email"
                  type="email"
                  required
                  autoComplete="email"
                  placeholder="you@example.com"
                />
              </label>
            )}
            <label>
              密码
              <input
                name="password"
                type="password"
                minLength={8}
                required
                autoComplete={
                  mode === "login" ? "current-password" : "new-password"
                }
                placeholder="至少 8 位"
              />
            </label>
            {error && <p className="form-error">{error}</p>}
            <button className="primary-button" disabled={busy}>
              {busy ? "请稍候…" : mode === "login" ? "进入训练台" : "创建账户"}{" "}
              <ArrowRight size={18} />
            </button>
          </form>
          <button
            className="text-button"
            onClick={() => {
              setMode(mode === "login" ? "register" : "login");
              setError("");
            }}
          >
            {mode === "login" ? "还没有账户？立即注册" : "已有账户？返回登录"}
          </button>
          <p className="security-note">
            <CheckCircle2 size={15} />
            刷新会话采用安全 HttpOnly Cookie
          </p>
        </div>
      </section>
    </main>
  );
}
