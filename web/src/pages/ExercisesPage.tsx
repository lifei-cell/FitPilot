import { useQuery } from "@tanstack/react-query";
import { Search } from "lucide-react";
import { useState } from "react";
import { api } from "../api/client";
import type { Exercise, PageResult } from "../api/types";
import { Empty, PageHeader, Panel } from "../components/PageParts";

export function ExercisesPage() {
  const [keyword, setKeyword] = useState("");
  const [selected, setSelected] = useState<Exercise | null>(null);
  const exercises = useQuery({
    queryKey: ["exercises", keyword],
    queryFn: () =>
      api<PageResult<Exercise>>(
        `/exercises?size=60&keyword=${encodeURIComponent(keyword)}`,
      ),
  });
  return (
    <main className="page">
      <PageHeader
        eyebrow="MOVEMENT LIBRARY"
        title="动作库"
        description="按目标肌群和器械找到合适动作，并查看标准说明。"
        action={
          <label className="search-box">
            <Search size={17} />
            <input
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              placeholder="搜索卧推、深蹲…"
            />
          </label>
        }
      />
      <div className="library-layout">
        <Panel>
          <div className="exercise-grid">
            {exercises.data?.items.map((item) => (
              <button
                className={`exercise-card ${selected?.id === item.id ? "selected" : ""}`}
                onClick={() => setSelected(item)}
                key={item.id}
              >
                <span>{item.category ?? "训练动作"}</span>
                <strong>{item.name}</strong>
                <small>{item.englishName}</small>
                <p>
                  {item.primaryMuscle ?? "全身"} · {item.equipment ?? "徒手"}
                </p>
                <em>ID {item.id}</em>
              </button>
            ))}
          </div>
          {!exercises.data?.items.length && (
            <Empty title="没有找到动作" text="换个关键词再试试。" />
          )}
        </Panel>
        <Panel className="exercise-detail">
          {selected ? (
            <>
              <p className="eyebrow">EXERCISE DETAIL</p>
              <h2>{selected.name}</h2>
              <p className="subtle">{selected.description ?? "暂无动作简介"}</p>
              <dl>
                <div>
                  <dt>主要肌群</dt>
                  <dd>{selected.primaryMuscle ?? "—"}</dd>
                </div>
                <div>
                  <dt>器械</dt>
                  <dd>{selected.equipment ?? "—"}</dd>
                </div>
                <div>
                  <dt>难度</dt>
                  <dd>{selected.difficulty ?? "—"}</dd>
                </div>
                <div>
                  <dt>动作 ID</dt>
                  <dd>{selected.id}</dd>
                </div>
              </dl>
              <h3>执行要点</h3>
              <p className="instruction">
                {selected.instructions ?? "保持稳定节奏和完整动作范围。"}
              </p>
            </>
          ) : (
            <Empty title="选择一个动作" text="详细说明会显示在这里。" />
          )}
        </Panel>
      </div>
    </main>
  );
}
