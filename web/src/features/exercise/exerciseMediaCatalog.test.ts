import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { expect, it } from "vitest";
import { findExerciseMedia } from "./exerciseMediaCatalog";

it("ships one image and a video for every seeded exercise", () => {
  const seed = readFileSync("../src/main/resources/db/migration/V2__seed_exercises.sql", "utf8");
  const exercises = [...seed.matchAll(/^\('([^']*)','([^']*)'/gm)];
  expect(exercises).toHaveLength(50);
  for (const [, name, englishName] of exercises) {
    const media = findExerciseMedia({ name, englishName });
    expect(media?.video.bvid, name).toMatch(/^BV[A-Za-z0-9]{10}$/);
    expect(media && existsSync(join("public", media.image.src)), name).toBe(true);
  }
  expect(findExerciseMedia({ name: "其他卧推变式", englishName: "Barbell Bench Press" })).toBeUndefined();
});
