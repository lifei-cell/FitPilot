import type { Exercise } from "../../api/types";

type ArtworkCredit = { label: string; url: string; license?: string };

const artwork: Record<"repdb" | "open-exercise-art", ArtworkCredit> = {
  repdb: { label: "Exercise data by RepDB (repdb.co)", url: "https://repdb.co/" },
  "open-exercise-art": {
    label: "Open Exercise Art / Alex Mollard",
    url: "https://github.com/AlexMollard/Open-Exercise-Art",
    license: "https://creativecommons.org/licenses/by-sa/4.0/",
  },
};

type ImageFile = `${keyof typeof artwork}/${string}`;
type Entry = readonly [name: string, imageFile: ImageFile, bvid: `BV${string}`, part?: number];

// Match the seed catalog by both names. Deployment-specific database IDs can differ.
const catalog = new Map<string, Entry>([
  ["Barbell Bench Press", ["杠铃卧推", "repdb/bench-press.webp", "BV1JF411g7ua"]],
  ["Back Squat", ["深蹲", "repdb/squat.webp", "BV1cT4y1a794"]],
  ["Deadlift", ["硬拉", "repdb/deadlift.webp", "BV1rr4y167CV"]],
  ["Overhead Press", ["站姿推举", "repdb/ohp.webp", "BV1Ve411Y7Cy"]],
  ["Pull Up", ["引体向上", "repdb/pull-up.webp", "BV1orhhzCEre"]],
  ["Lat Pulldown", ["高位下拉", "repdb/lat-pulldown.webp", "BV1usAJeuEQa"]],
  ["Barbell Row", ["杠铃划船", "repdb/barbell-row.webp", "BV1jT4y1e7Ue"]],
  ["Seated Cable Row", ["坐姿划船", "repdb/seated-cable-row.webp", "BV1DpbX6uEur"]],
  ["Incline Dumbbell Press", ["上斜哑铃卧推", "repdb/incline-db-press.webp", "BV1HF411T7kE"]],
  ["Dumbbell Lateral Raise", ["哑铃侧平举", "repdb/lateral-raise.webp", "BV1mZ4y187no"]],
  ["Dumbbell Curl", ["哑铃弯举", "repdb/bicep-curl.webp", "BV1eY4y187XT"]],
  ["Triceps Pushdown", ["绳索下压", "repdb/tricep-pushdown.webp", "BV1fjMyzZEt5"]],
  ["Front Squat", ["前蹲", "repdb/front-squat.webp", "BV1JY411P75i"]],
  ["Romanian Deadlift", ["罗马尼亚硬拉", "repdb/romanian-deadlift.webp", "BV1FE411C7go"]],
  ["Leg Press", ["腿举", "repdb/leg-press.webp", "BV1zB4y1S7aY"]],
  ["Bulgarian Split Squat", ["保加利亚分腿蹲", "repdb/bulgarian-split-squat.webp", "BV1H3xHz1EPm"]],
  ["Leg Extension", ["腿屈伸", "repdb/leg-extension.webp", "BV1Yg41197WG"]],
  ["Leg Curl", ["腿弯举", "repdb/leg-curl.webp", "BV1qB4y1S7SE"]],
  ["Hip Thrust", ["臀推", "repdb/hip-thrust.webp", "BV1Dt4y1a7EV"]],
  ["Standing Calf Raise", ["站姿提踵", "repdb/standing-calf-raise.webp", "BV1zS4y1c77u", 38]],
  ["Push Up", ["俯卧撑", "repdb/push-up.webp", "BV1Ui4y1b7Xk"]],
  ["Dips", ["双杠臂屈伸", "repdb/dips.webp", "BV1e64y1m7vY"]],
  ["Close Grip Bench Press", ["窄距卧推", "repdb/close-grip-bench-press.webp", "BV14m421E71T"]],
  ["Cable Fly", ["飞鸟", "repdb/cable-fly.webp", "BV1F3411i78u"]],
  ["Reverse Fly", ["反向飞鸟", "repdb/dumbbell-reverse-fly.webp", "BV1pr4y147HP"]],
  ["Face Pull", ["面拉", "repdb/face-pull.webp", "BV18tQBYfEZc"]],
  ["Arnold Press", ["阿诺德推举", "repdb/arnold-press.webp", "BV1gjpizZEyf"]],
  ["Hammer Curl", ["锤式弯举", "repdb/hammer-curl.webp", "BV19r4y1b7K9"]],
  ["Preacher Curl", ["牧师凳弯举", "repdb/barbell-preacher-curl.webp", "BV1MJ411k75W", 35]],
  ["Skull Crusher", ["仰卧臂屈伸", "repdb/skull-crusher.webp", "BV1za411Y7RN"]],
  ["Farmers Walk", ["农夫走", "repdb/dumbbell-farmers-walk.webp", "BV1VUB1YHEcK"]],
  ["Plank", ["平板支撑", "repdb/plank.webp", "BV14w411V7Yj"]],
  ["Hanging Leg Raise", ["悬垂举腿", "repdb/hanging-leg-raise.webp", "BV1zS4y1c77u", 13]],
  ["Russian Twist", ["俄罗斯转体", "repdb/russian-twist.webp", "BV1t64y1c7Et"]],
  ["Kettlebell Swing", ["壶铃摆动", "repdb/kettlebell-swing.webp", "BV1KDsaexEsV"]],
  ["Burpee", ["波比跳", "repdb/burpees.webp", "BV1tZ4y1T7Cu"]],
  ["Jumping Jack", ["开合跳", "repdb/jumping-jacks.webp", "BV1kJ411u7jW"]],
  ["Mountain Climber", ["登山跑", "repdb/mountain-climbers.webp", "BV1At4y1M7rk"]],
  ["Treadmill Run", ["跑步机跑步", "repdb/treadmill-running.webp", "BV1n1ADzvEG5"]],
  ["Rowing Machine", ["划船机", "repdb/rowing-machine.webp", "BV1aF411K7yz"]],
  ["Elliptical", ["椭圆机", "repdb/elliptical-trainer.webp", "BV1oM4y127ZC"]],
  ["Stationary Bike", ["动感单车", "repdb/stationary-bike.webp", "BV15H4y1m74e"]],
  ["Cat Cow", ["猫牛式", "repdb/cat-cow.webp", "BV1zE411B7sF"]],
  ["Worlds Greatest Stretch", ["世界最佳拉伸", "repdb/worlds-greatest-stretch.webp", "BV1ce411v7Yb"]],
  ["Hip Flexor Stretch", ["髋屈肌拉伸", "repdb/kneeling-hip-flexor-stretch.webp", "BV1aC411j7LH"]],
  ["Shoulder Circles", ["肩关节绕环", "open-exercise-art/arm-circles.png", "BV1714y1G7v1"]],
  ["Thoracic Rotation", ["胸椎旋转", "repdb/pilates-spine-twist.webp", "BV1boJmzEEQN"]],
  ["Ankle Dorsiflexion", ["脚踝背屈", "open-exercise-art/knee-to-wall-dorsiflexion.png", "BV1Kt421M7Ao"]],
  ["Pigeon Pose", ["鸽子式", "repdb/pigeon-stretch.webp", "BV1vK4y1U75N"]],
  ["Child Pose", ["儿童式", "repdb/childs-pose.webp", "BV1eD4y1m7VU"]],
]);

export function findExerciseMedia({ name, englishName }: Pick<Exercise, "name" | "englishName">) {
  const entry = englishName && catalog.get(englishName);
  if (!entry || entry[0] !== name) return undefined;

  const [, imageFile, bvid, part] = entry;
  const lightLineArt = imageFile.startsWith("open-exercise-art/");
  const credit = lightLineArt ? artwork["open-exercise-art"] : artwork.repdb;
  const src = `${import.meta.env.BASE_URL}exercise-media/${imageFile}`;
  return { image: { src, credit, lightLineArt }, video: { bvid, part } };
}
