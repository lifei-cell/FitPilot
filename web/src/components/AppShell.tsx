import type { PropsWithChildren } from "react";
import {
  BarChart3,
  Bell,
  Bot,
  CalendarRange,
  Dumbbell,
  LayoutDashboard,
  Library,
  LogOut,
  UserRound,
} from "lucide-react";
import { Brand } from "./Brand";
import { useAuth } from "../auth/AuthContext";

const nav = [
  ["/", "概览", LayoutDashboard],
  ["/plans", "训练计划", CalendarRange],
  ["/workouts", "开始训练", Dumbbell],
  ["/exercises", "动作库", Library],
  ["/progress", "进度分析", BarChart3],
  ["/coach", "AI 教练", Bot],
  ["/notifications", "通知", Bell],
  ["/profile", "个人档案", UserRound],
] as const;

export function navigate(path: string) {
  history.pushState({}, "", path);
  window.dispatchEvent(new PopStateEvent("popstate"));
}

export function AppShell({ children }: PropsWithChildren) {
  const auth = useAuth();
  return (
    <div className="app-frame">
      <aside className="sidebar">
        <Brand />
        <nav>
          {nav.map(([path, label, Icon]) => (
            <a
              key={path}
              href={path}
              className={location.pathname === path ? "active" : ""}
              onClick={(e) => {
                e.preventDefault();
                navigate(path);
              }}
            >
              <Icon size={19} />
              <span>{label}</span>
            </a>
          ))}
        </nav>
        <button className="sidebar-logout" onClick={() => void auth.logout()}>
          <LogOut size={18} />
          退出登录
        </button>
      </aside>
      <div className="mobile-nav">
        <Brand compact />
        {nav.slice(0, 6).map(([path, label, Icon]) => (
          <a
            key={path}
            aria-label={label}
            href={path}
            onClick={(e) => {
              e.preventDefault();
              navigate(path);
            }}
          >
            <Icon size={20} />
          </a>
        ))}
      </div>
      <div className="app-main">{children}</div>
    </div>
  );
}
