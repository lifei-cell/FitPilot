import { useQuery } from "@tanstack/react-query";
import { Plus, Search } from "lucide-react";
import { useDeferredValue, useState } from "react";
import { api } from "../../api/client";
import type { Exercise, PageResult } from "../../api/types";

type Props = {
  selectedIds: number[];
  onSelect(exercise: Exercise): void;
};

export function ExerciseSearchPicker({ selectedIds, onSelect }: Props) {
  const [keyword, setKeyword] = useState("");
  const deferredKeyword = useDeferredValue(keyword.trim());
  const results = useQuery({
    queryKey: ["exercise-search", deferredKeyword],
    queryFn: () =>
      api<PageResult<Exercise>>(
        `/exercises?size=20${
          deferredKeyword
            ? `&keyword=${encodeURIComponent(deferredKeyword)}`
            : ""
        }`,
      ),
    staleTime: 5 * 60_000,
  });

  return (
    <div className="exercise-picker">
      <label className="exercise-picker-search">
        <Search size={15} />
        <input
          value={keyword}
          maxLength={80}
          placeholder="搜索动作名称，例如：深蹲"
          onChange={(event) => setKeyword(event.target.value)}
        />
      </label>
      <div className="exercise-picker-results">
        {results.isPending && <p>正在加载动作…</p>}
        {results.error && <p className="form-error">{results.error.message}</p>}
        {results.data?.items.map((exercise) => {
          const selected = selectedIds.includes(exercise.id);
          return (
            <button
              type="button"
              key={exercise.id}
              disabled={selected}
              onClick={() => onSelect(exercise)}
            >
              <span>
                <strong>{exercise.name}</strong>
                <small>
                  {exercise.primaryMuscle ?? "综合"} · {exercise.equipment ?? "不限器械"}
                </small>
              </span>
              {selected ? "已添加" : <Plus size={15} />}
            </button>
          );
        })}
        {results.data && results.data.items.length === 0 && (
          <p>没有找到匹配动作。</p>
        )}
      </div>
    </div>
  );
}
