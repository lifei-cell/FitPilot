BEGIN;

-- Explicit development/demo seed. It only writes rows owned by demo_athlete.
INSERT INTO users(username, email, password_hash, status)
VALUES ('demo_athlete', 'demo@fitpilot.local', '$2a$10$ezUIY3B1v.Obcxm972h8xOU2qpv6k91n3bPcHTmrVwK0b/RwqPFTy', 1)
ON CONFLICT (username) DO NOTHING;

INSERT INTO user_profile(user_id, gender, birthday, height_cm, training_experience_months,
                         training_goal, weekly_frequency, preferred_duration_minutes)
SELECT id, 1, DATE '1998-06-18', 178.00, 18, 'MUSCLE_GAIN', 3, 75
FROM users WHERE username = 'demo_athlete'
ON CONFLICT (user_id) DO UPDATE SET
    gender = EXCLUDED.gender,
    birthday = EXCLUDED.birthday,
    height_cm = EXCLUDED.height_cm,
    training_experience_months = EXCLUDED.training_experience_months,
    training_goal = EXCLUDED.training_goal,
    weekly_frequency = EXCLUDED.weekly_frequency,
    preferred_duration_minutes = EXCLUDED.preferred_duration_minutes,
    updated_at = CURRENT_TIMESTAMP;

WITH duplicate_metrics AS (
    SELECT b.id, ROW_NUMBER() OVER (PARTITION BY b.user_id, b.recorded_at::date ORDER BY b.id) AS rank
    FROM body_metric b JOIN users u ON u.id = b.user_id
    WHERE u.username = 'demo_athlete'
)
DELETE FROM body_metric b USING duplicate_metrics d WHERE b.id = d.id AND d.rank > 1;

WITH demo_user AS (SELECT id FROM users WHERE username = 'demo_athlete'),
metrics(recorded_at, weight_kg, body_fat_percentage, muscle_mass_kg) AS (
    VALUES
        (CURRENT_TIMESTAMP - INTERVAL '42 days', 76.80::numeric, 18.60::numeric, 57.20::numeric),
        (CURRENT_TIMESTAMP - INTERVAL '35 days', 76.30::numeric, 18.30::numeric, 57.50::numeric),
        (CURRENT_TIMESTAMP - INTERVAL '28 days', 75.90::numeric, 18.00::numeric, 57.80::numeric),
        (CURRENT_TIMESTAMP - INTERVAL '21 days', 75.60::numeric, 17.80::numeric, 58.00::numeric),
        (CURRENT_TIMESTAMP - INTERVAL '14 days', 75.20::numeric, 17.50::numeric, 58.20::numeric),
        (CURRENT_TIMESTAMP - INTERVAL '7 days', 74.90::numeric, 17.20::numeric, 58.40::numeric),
        (CURRENT_TIMESTAMP - INTERVAL '1 day', 74.60::numeric, 16.90::numeric, 58.70::numeric)
)
INSERT INTO body_metric(user_id, weight_kg, body_fat_percentage, muscle_mass_kg, recorded_at)
SELECT u.id, m.weight_kg, m.body_fat_percentage, m.muscle_mass_kg, m.recorded_at
FROM demo_user u CROSS JOIN metrics m
WHERE NOT EXISTS (
    SELECT 1 FROM body_metric b
    WHERE b.user_id = u.id AND b.recorded_at::date = m.recorded_at::date
);

WITH demo_user AS (SELECT id FROM users WHERE username = 'demo_athlete')
INSERT INTO training_plan(user_id, name, description, goal, duration_weeks, days_per_week, status, started_at)
SELECT id, '12 周力量增肌计划', '演示用三分化计划：以基础力量和可持续增肌为目标。',
       'MUSCLE_GAIN', 12, 3, 'ACTIVE', CURRENT_DATE - 21
FROM demo_user
WHERE NOT EXISTS (
    SELECT 1 FROM training_plan p WHERE p.user_id = demo_user.id AND p.status = 'ACTIVE'
);

WITH demo_plan AS (
    SELECT p.id FROM training_plan p JOIN users u ON u.id = p.user_id
    WHERE u.username = 'demo_athlete' AND p.status = 'ACTIVE'
), days(day_number, name, notes) AS (
    VALUES (1, '上肢力量', '推拉平衡，主项优先。'),
           (3, '下肢力量', '深蹲主导，兼顾后侧链。'),
           (5, '全身增肌', '中等强度，补足训练容量。')
)
INSERT INTO training_plan_day(training_plan_id, day_number, name, notes)
SELECT p.id, d.day_number, d.name, d.notes FROM demo_plan p CROSS JOIN days d
ON CONFLICT (training_plan_id, day_number) DO UPDATE SET name = EXCLUDED.name, notes = EXCLUDED.notes;

WITH demo_plan AS (
    SELECT p.id FROM training_plan p JOIN users u ON u.id = p.user_id
    WHERE u.username = 'demo_athlete' AND p.status = 'ACTIVE'
), prescriptions(day_number, exercise_name, sequence, target_sets, reps_min, reps_max, target_rpe, rest_seconds, notes) AS (
    VALUES
        (1, '杠铃卧推', 1, 4, 6, 8, 8.0::numeric, 150, '最后一组保留 1-2 次余力'),
        (1, '杠铃划船', 2, 4, 8, 10, 8.0::numeric, 120, '保持躯干稳定'),
        (1, '站姿推举', 3, 3, 8, 10, 7.5::numeric, 120, '核心收紧'),
        (1, '哑铃弯举', 4, 3, 10, 12, 8.0::numeric, 75, '控制离心'),
        (3, '深蹲', 1, 4, 5, 6, 8.0::numeric, 180, '深度稳定优先'),
        (3, '罗马尼亚硬拉', 2, 3, 8, 10, 8.0::numeric, 150, '髋部后移'),
        (3, '腿举', 3, 3, 10, 12, 8.0::numeric, 120, '全幅度控制'),
        (3, '平板支撑', 4, 3, 30, 45, 7.0::numeric, 60, '每组以秒计'),
        (5, '上斜哑铃卧推', 1, 3, 8, 10, 8.0::numeric, 120, '上胸发力'),
        (5, '引体向上', 2, 3, 6, 10, 8.0::numeric, 120, '全程悬垂'),
        (5, '臀推', 3, 3, 8, 10, 8.0::numeric, 120, '顶端停顿'),
        (5, '哑铃侧平举', 4, 3, 12, 15, 8.0::numeric, 60, '轻重量全程')
)
INSERT INTO training_plan_exercise(training_plan_day_id, exercise_id, sequence, target_sets,
                                   target_reps_min, target_reps_max, target_rpe, rest_seconds, notes)
SELECT d.id, e.id, p.sequence, p.target_sets, p.reps_min, p.reps_max, p.target_rpe, p.rest_seconds, p.notes
FROM prescriptions p
JOIN demo_plan dp ON TRUE
JOIN training_plan_day d ON d.training_plan_id = dp.id AND d.day_number = p.day_number
JOIN exercise e ON e.name = p.exercise_name
ON CONFLICT (training_plan_day_id, sequence) DO UPDATE SET
    exercise_id = EXCLUDED.exercise_id, target_sets = EXCLUDED.target_sets,
    target_reps_min = EXCLUDED.target_reps_min, target_reps_max = EXCLUDED.target_reps_max,
    target_rpe = EXCLUDED.target_rpe, rest_seconds = EXCLUDED.rest_seconds, notes = EXCLUDED.notes;

WITH demo_user AS (SELECT id FROM users WHERE username = 'demo_athlete'),
demo_plan AS (
    SELECT p.id FROM training_plan p JOIN demo_user u ON u.id = p.user_id WHERE p.status = 'ACTIVE'
), sessions(name, day_number, started_at, completed_at, duration_seconds) AS (
    VALUES
        ('演示·上肢力量 #01', 1, CURRENT_TIMESTAMP - INTERVAL '13 days', CURRENT_TIMESTAMP - INTERVAL '13 days' + INTERVAL '72 minutes', 4320),
        ('演示·下肢力量 #01', 3, CURRENT_TIMESTAMP - INTERVAL '10 days', CURRENT_TIMESTAMP - INTERVAL '10 days' + INTERVAL '76 minutes', 4560),
        ('演示·全身增肌 #01', 5, CURRENT_TIMESTAMP - INTERVAL '7 days', CURRENT_TIMESTAMP - INTERVAL '7 days' + INTERVAL '69 minutes', 4140),
        ('演示·上肢力量 #02', 1, CURRENT_TIMESTAMP - INTERVAL '3 days', CURRENT_TIMESTAMP - INTERVAL '3 days' + INTERVAL '74 minutes', 4440),
        ('演示·下肢力量 #02', 3, CURRENT_TIMESTAMP - INTERVAL '1 day', CURRENT_TIMESTAMP - INTERVAL '1 day' + INTERVAL '78 minutes', 4680)
)
INSERT INTO workout(user_id, training_plan_id, training_plan_day_id, name, status, started_at, completed_at, duration_seconds, notes)
SELECT u.id, p.id, d.id, s.name, 'COMPLETED', s.started_at, s.completed_at, s.duration_seconds, 'FitPilot 演示训练数据'
FROM demo_user u CROSS JOIN demo_plan p
JOIN sessions s ON TRUE
JOIN training_plan_day d ON d.training_plan_id = p.id AND d.day_number = s.day_number
WHERE NOT EXISTS (SELECT 1 FROM workout w WHERE w.user_id = u.id AND w.name = s.name);

WITH demo_user AS (SELECT id FROM users WHERE username = 'demo_athlete'),
snapshots(workout_name, exercise_name, sequence, target_sets, reps_min, reps_max, target_rpe, rest_seconds) AS (
    VALUES
        ('演示·上肢力量 #01', '杠铃卧推', 1, 4, 6, 8, 8.0::numeric, 150),
        ('演示·下肢力量 #01', '深蹲', 1, 4, 5, 6, 8.0::numeric, 180),
        ('演示·全身增肌 #01', '上斜哑铃卧推', 1, 3, 8, 10, 8.0::numeric, 120),
        ('演示·上肢力量 #02', '杠铃卧推', 1, 4, 6, 8, 8.0::numeric, 150),
        ('演示·下肢力量 #02', '深蹲', 1, 4, 5, 6, 8.0::numeric, 180)
)
INSERT INTO workout_exercise(workout_id, exercise_id, sequence, exercise_name, target_sets,
                             target_reps_min, target_reps_max, target_rpe, rest_seconds, notes)
SELECT w.id, e.id, s.sequence, e.name, s.target_sets, s.reps_min, s.reps_max, s.target_rpe, s.rest_seconds,
       '演示快照：计划变更不会影响历史训练'
FROM snapshots s
JOIN demo_user u ON TRUE
JOIN workout w ON w.user_id = u.id AND w.name = s.workout_name
JOIN exercise e ON e.name = s.exercise_name
ON CONFLICT (workout_id, sequence) DO NOTHING;

WITH demo_user AS (SELECT id FROM users WHERE username = 'demo_athlete'),
set_data(workout_name, exercise_name, set_number, weight_kg, reps, rpe) AS (
    VALUES
        ('演示·上肢力量 #01', '杠铃卧推', 1, 67.5::numeric, 8, 7.5::numeric),
        ('演示·上肢力量 #01', '杠铃卧推', 2, 70.0::numeric, 8, 8.0::numeric),
        ('演示·上肢力量 #01', '杠铃卧推', 3, 72.5::numeric, 7, 8.5::numeric),
        ('演示·下肢力量 #01', '深蹲', 1, 92.5::numeric, 6, 7.5::numeric),
        ('演示·下肢力量 #01', '深蹲', 2, 95.0::numeric, 6, 8.0::numeric),
        ('演示·下肢力量 #01', '深蹲', 3, 97.5::numeric, 5, 8.5::numeric),
        ('演示·全身增肌 #01', '上斜哑铃卧推', 1, 27.5::numeric, 10, 7.5::numeric),
        ('演示·全身增肌 #01', '上斜哑铃卧推', 2, 30.0::numeric, 9, 8.0::numeric),
        ('演示·全身增肌 #01', '上斜哑铃卧推', 3, 30.0::numeric, 8, 8.5::numeric),
        ('演示·上肢力量 #02', '杠铃卧推', 1, 72.5::numeric, 8, 8.0::numeric),
        ('演示·上肢力量 #02', '杠铃卧推', 2, 75.0::numeric, 7, 8.5::numeric),
        ('演示·上肢力量 #02', '杠铃卧推', 3, 75.0::numeric, 6, 9.0::numeric),
        ('演示·下肢力量 #02', '深蹲', 1, 97.5::numeric, 6, 8.0::numeric),
        ('演示·下肢力量 #02', '深蹲', 2, 100.0::numeric, 5, 8.5::numeric),
        ('演示·下肢力量 #02', '深蹲', 3, 102.5::numeric, 5, 9.0::numeric)
)
INSERT INTO workout_set(workout_exercise_id, set_number, weight_kg, reps, rpe, is_warmup, is_failure, completed_at)
SELECT we.id, s.set_number, s.weight_kg, s.reps, s.rpe, FALSE, FALSE, w.completed_at - INTERVAL '5 minutes'
FROM set_data s
JOIN demo_user u ON TRUE
JOIN workout w ON w.user_id = u.id AND w.name = s.workout_name
JOIN workout_exercise we ON we.workout_id = w.id AND we.exercise_name = s.exercise_name
ON CONFLICT (workout_exercise_id, set_number) DO NOTHING;

WITH demo_user AS (SELECT id FROM users WHERE username = 'demo_athlete')
INSERT INTO workout_analytics_projection(workout_id, user_id, completed_at, duration_seconds,
                                          training_volume, completed_set_count, projected_at)
SELECT w.id, u.id, w.completed_at, w.duration_seconds,
       COALESCE(SUM(ws.weight_kg * ws.reps), 0), COUNT(ws.id), CURRENT_TIMESTAMP
FROM demo_user u
JOIN workout w ON w.user_id = u.id AND w.name LIKE '演示·%'
LEFT JOIN workout_exercise we ON we.workout_id = w.id
LEFT JOIN workout_set ws ON ws.workout_exercise_id = we.id AND ws.is_warmup = FALSE
GROUP BY w.id, u.id, w.completed_at, w.duration_seconds
ON CONFLICT (workout_id) DO UPDATE SET
    completed_at = EXCLUDED.completed_at, duration_seconds = EXCLUDED.duration_seconds,
    training_volume = EXCLUDED.training_volume, completed_set_count = EXCLUDED.completed_set_count,
    projected_at = EXCLUDED.projected_at;

WITH ranked_sets AS (
    SELECT u.id AS user_id, we.exercise_id, w.id AS workout_id, ws.id AS workout_set_id,
           ws.weight_kg, ws.reps, ws.completed_at,
           ROUND(ws.weight_kg * (1 + ws.reps / 30.0), 2) AS estimated_1rm,
           ROW_NUMBER() OVER (PARTITION BY we.exercise_id ORDER BY ws.weight_kg DESC, ws.completed_at DESC) AS rank
    FROM users u JOIN workout w ON w.user_id = u.id
    JOIN workout_exercise we ON we.workout_id = w.id
    JOIN workout_set ws ON ws.workout_exercise_id = we.id
    WHERE u.username = 'demo_athlete' AND w.name LIKE '演示·%' AND ws.is_warmup = FALSE
), records(record_type) AS (VALUES ('MAX_WEIGHT'), ('ESTIMATED_1RM'), ('MAX_VOLUME'))
INSERT INTO personal_record(user_id, exercise_id, record_type, weight_kg, reps, estimated_1rm,
                            workout_id, workout_set_id, achieved_at)
SELECT s.user_id, s.exercise_id, r.record_type, s.weight_kg, s.reps, s.estimated_1rm,
       s.workout_id, s.workout_set_id, s.completed_at
FROM ranked_sets s CROSS JOIN records r
WHERE s.rank = 1
ON CONFLICT (workout_set_id, record_type) DO NOTHING;

WITH demo_user AS (SELECT id FROM users WHERE username = 'demo_athlete'),
notifications(source_event_id, title, message, created_at) AS (
    VALUES
        ('f4d7335a-2f35-47bc-bd9b-000000000001'::uuid, '新的个人纪录', '恭喜！杠铃卧推已刷新演示个人纪录。', CURRENT_TIMESTAMP - INTERVAL '3 days'),
        ('f4d7335a-2f35-47bc-bd9b-000000000002'::uuid, '新的个人纪录', '恭喜！深蹲已刷新演示个人纪录。', CURRENT_TIMESTAMP - INTERVAL '1 day')
)
INSERT INTO user_notification(user_id, source_event_id, type, title, message, is_read, created_at)
SELECT u.id, n.source_event_id, 'PERSONAL_RECORD', n.title, n.message, FALSE, n.created_at
FROM demo_user u CROSS JOIN notifications n
ON CONFLICT (source_event_id) DO NOTHING;

INSERT INTO knowledge_document(id, external_id, title, category, source_url, source_license, format,
                               content_hash, raw_content, metadata, index_status)
VALUES (
    'f4d7335a-2f35-47bc-bd9b-000000000010'::uuid,
    'demo-bench-press-guide-v1',
    '杠铃卧推演示指南',
    'STRENGTH_TRAINING',
    'https://fitpilot.local/demo/bench-press-guide',
    'INTERNAL_DEMO',
    'MARKDOWN',
    repeat('a', 64),
    E'# 杠铃卧推\n\n演示建议：肩胛后缩下沉，双脚稳定踩地。先用可控重量完成热身，再以 RPE 7-8 完成工作组。下放时保持前臂接近垂直，推起时避免耸肩。若出现肩前侧疼痛，应停止加重量并检查握距与活动范围。',
    '{"seed":"demo","language":"zh-CN"}'::jsonb,
    'PENDING'
)
ON CONFLICT (external_id) DO UPDATE SET
    title = EXCLUDED.title, raw_content = EXCLUDED.raw_content, metadata = EXCLUDED.metadata,
    index_status = CASE WHEN knowledge_document.index_status = 'INDEXED' THEN 'INDEXED' ELSE 'PENDING' END,
    updated_at = CURRENT_TIMESTAMP;

COMMIT;
