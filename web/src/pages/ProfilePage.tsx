import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, type FormEvent } from "react";
import { Save, Scale, UserRound } from "lucide-react";
import { api } from "../api/client";
import { PageHeader, Panel, SectionTitle } from "../components/PageParts";
type Profile = {
  id: number;
  username: string;
  email: string;
  gender?: number;
  birthday?: string;
  heightCm?: number;
  trainingExperienceMonths?: number;
  trainingGoal?: string;
  weeklyFrequency?: number;
  preferredDurationMinutes?: number;
};
type Metric = {
  id: number;
  weightKg: number;
  bodyFatPercentage?: number;
  muscleMassKg?: number;
  recordedAt: string;
};
export function ProfilePage() {
  const client = useQueryClient();
  const profile = useQuery({
    queryKey: ["profile"],
    queryFn: () => api<Profile>("/users/me"),
  });
  const metric = useQuery({
    queryKey: ["latest-metric"],
    queryFn: () => api<Metric>("/users/me/body-metrics/latest"),
    retry: false,
  });
  const update = useMutation({
    mutationFn: (payload: unknown) =>
      api("/users/me/profile", {
        method: "PUT",
        body: JSON.stringify(payload),
      }),
    onSuccess: () => client.invalidateQueries({ queryKey: ["profile"] }),
  });
  const addMetric = useMutation({
    mutationFn: (payload: unknown) =>
      api("/users/me/body-metrics", {
        method: "POST",
        body: JSON.stringify(payload),
      }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ["latest-metric"] });
      client.invalidateQueries({ queryKey: ["weight-trend"] });
    },
  });
  function saveProfile(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const d = new FormData(e.currentTarget);
    update.mutate({
      gender: Number(d.get("gender")),
      birthday: d.get("birthday") || null,
      heightCm: Number(d.get("heightCm")) || null,
      trainingExperienceMonths: Number(d.get("experience")) || 0,
      trainingGoal: d.get("goal"),
      weeklyFrequency: Number(d.get("frequency")),
      preferredDurationMinutes: Number(d.get("duration")),
    });
  }
  function saveMetric(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const d = new FormData(e.currentTarget);
    addMetric.mutate({
      weightKg: Number(d.get("weightKg")),
      bodyFatPercentage: Number(d.get("bodyFat")) || null,
      muscleMassKg: Number(d.get("muscle")) || null,
    });
  }
  useEffect(() => {}, [profile.data]);
  return (
    <main className="page">
      <PageHeader
        eyebrow="ATHLETE PROFILE"
        title="个人档案"
        description="让计划与建议更贴近你的身体和训练习惯。"
      />
      <div className="profile-grid">
        <Panel>
          <SectionTitle
            title="基础与训练偏好"
            detail={profile.data?.username}
          />
          <form
            className="form-grid"
            onSubmit={saveProfile}
            key={profile.data?.id}
          >
            <label>
              用户名
              <input value={profile.data?.username ?? ""} disabled />
            </label>
            <label>
              邮箱
              <input value={profile.data?.email ?? ""} disabled />
            </label>
            <label>
              性别
              <select name="gender" defaultValue={profile.data?.gender ?? 0}>
                <option value="0">未设置</option>
                <option value="1">男</option>
                <option value="2">女</option>
              </select>
            </label>
            <label>
              生日
              <input
                name="birthday"
                type="date"
                defaultValue={profile.data?.birthday}
              />
            </label>
            <label>
              身高（cm）
              <input
                name="heightCm"
                type="number"
                min="50"
                max="260"
                step=".1"
                defaultValue={profile.data?.heightCm}
              />
            </label>
            <label>
              训练经验（月）
              <input
                name="experience"
                type="number"
                min="0"
                defaultValue={profile.data?.trainingExperienceMonths ?? 0}
              />
            </label>
            <label>
              主要目标
              <select
                name="goal"
                defaultValue={profile.data?.trainingGoal ?? "STRENGTH"}
              >
                <option value="STRENGTH">力量</option>
                <option value="MUSCLE_GAIN">增肌</option>
                <option value="FAT_LOSS">减脂</option>
                <option value="GENERAL_FITNESS">综合体能</option>
              </select>
            </label>
            <label>
              每周频率
              <input
                name="frequency"
                type="number"
                min="1"
                max="7"
                defaultValue={profile.data?.weeklyFrequency ?? 3}
              />
            </label>
            <label>
              单次时长（分钟）
              <input
                name="duration"
                type="number"
                min="15"
                max="300"
                defaultValue={profile.data?.preferredDurationMinutes ?? 60}
              />
            </label>
            <button className="primary-button wide">
              <Save size={17} />
              保存档案
            </button>
          </form>
        </Panel>
        <Panel>
          <div className="profile-avatar">
            <UserRound size={34} />
          </div>
          <h2>{profile.data?.username ?? "训练者"}</h2>
          <p className="subtle">
            {profile.data?.trainingGoal ?? "尚未设置目标"} · 每周{" "}
            {profile.data?.weeklyFrequency ?? "—"} 练
          </p>
          <div className="latest-metric">
            <Scale />
            <div>
              <small>最新体重</small>
              <strong>
                {metric.data?.weightKg ?? "—"} <em>kg</em>
              </strong>
            </div>
          </div>
          <SectionTitle title="记录身体数据" />
          <form className="metric-form" onSubmit={saveMetric}>
            <label>
              体重
              <input
                name="weightKg"
                type="number"
                step=".1"
                min="20"
                max="500"
                required
                placeholder="kg"
              />
            </label>
            <label>
              体脂率
              <input
                name="bodyFat"
                type="number"
                step=".1"
                min="1"
                max="70"
                placeholder="%"
              />
            </label>
            <label>
              肌肉量
              <input
                name="muscle"
                type="number"
                step=".1"
                min="1"
                max="300"
                placeholder="kg"
              />
            </label>
            <button className="secondary-button">添加记录</button>
          </form>
        </Panel>
      </div>
    </main>
  );
}
