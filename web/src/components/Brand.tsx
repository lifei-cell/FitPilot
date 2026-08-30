import { Activity } from "lucide-react";

export function Brand({ compact = false }: { compact?: boolean }) {
  return (
    <a className="brand" href="/" aria-label="FitPilot 首页">
      <span className="brand-mark">
        <Activity size={20} strokeWidth={2.4} />
      </span>
      {!compact && (
        <span>
          FIT<span>PILOT</span>
        </span>
      )}
    </a>
  );
}
