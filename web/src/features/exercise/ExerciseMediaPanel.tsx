import { useId, useState } from "react";
import type { Exercise } from "../../api/types";
import { findExerciseMedia } from "./exerciseMediaCatalog";
import "./exercise-media-panel.css";

export function ExerciseMediaPanel({ exercise }: { exercise: Exercise }) {
  const [imageUnavailable, setImageUnavailable] = useState(false);
  const [videoOpen, setVideoOpen] = useState(false);
  const playerId = useId();
  const media = findExerciseMedia(exercise);
  if (!media) return null;

  const query = new URLSearchParams({ bvid: media.video.bvid, autoplay: "1", danmaku: "0" });
  if (media.video.part) query.set("p", String(media.video.part));
  const watchUrl = new URL(`https://www.bilibili.com/video/${media.video.bvid}/`);
  if (media.video.part) watchUrl.searchParams.set("p", String(media.video.part));

  return (
    <div className="exercise-media">
      <figure className="exercise-media-image">
        {imageUnavailable ? <p className="subtle">暂时无法加载动作图。</p> : (
          <img className={media.image.lightLineArt ? "exercise-media-line-art" : undefined}
            src={media.image.src} alt={`${exercise.name}动作示意图`} loading="lazy"
            decoding="async" onError={() => setImageUnavailable(true)} />
        )}
        <figcaption>
          <a href={media.image.credit.url} target="_blank" rel="noreferrer">{media.image.credit.label}</a>
          {media.image.credit.license && <> · <a href={media.image.credit.license}
            target="_blank" rel="noreferrer">CC BY-SA 4.0</a></>}
        </figcaption>
      </figure>
      <div className="exercise-media-actions">
        <button type="button" className="secondary-button" aria-expanded={videoOpen}
          aria-controls={playerId} onClick={() => setVideoOpen((open) => !open)}>
          {videoOpen ? "收起视频" : "观看教学视频"}
        </button>
        <a href={watchUrl.href} target="_blank" rel="noreferrer">在 B 站打开</a>
      </div>
      <div id={playerId} hidden={!videoOpen}>
        {videoOpen && <iframe className="exercise-media-player" title={`${exercise.name}教学视频`}
          src={`https://player.bilibili.com/player.html?${query}`} allow="autoplay" allowFullScreen />}
      </div>
    </div>
  );
}
