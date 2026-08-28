CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(100) UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    status SMALLINT NOT NULL DEFAULT 1 CHECK (status IN (0, 1, 2)),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_profile (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    gender SMALLINT,
    birthday DATE,
    height_cm NUMERIC(5,2),
    training_experience_months INT,
    training_goal VARCHAR(32),
    weekly_frequency INT,
    preferred_duration_minutes INT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE body_metric (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    weight_kg NUMERIC(5,2) NOT NULL,
    body_fat_percentage NUMERIC(5,2),
    muscle_mass_kg NUMERIC(5,2),
    recorded_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_body_metric_user_time ON body_metric(user_id, recorded_at DESC);

CREATE TABLE exercise (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    english_name VARCHAR(100),
    category VARCHAR(50),
    equipment VARCHAR(50),
    difficulty VARCHAR(20),
    primary_muscle VARCHAR(50),
    secondary_muscles VARCHAR(255),
    description TEXT,
    instructions TEXT,
    status SMALLINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_exercise_filters ON exercise(status, category, equipment, primary_muscle);

CREATE TABLE training_plan (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    goal VARCHAR(32),
    duration_weeks INT,
    days_per_week INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    version INT NOT NULL DEFAULT 1,
    started_at DATE,
    ended_at DATE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_training_plan_user_status ON training_plan(user_id, status);
CREATE UNIQUE INDEX uk_training_plan_one_active ON training_plan(user_id) WHERE status = 'ACTIVE';

CREATE TABLE training_plan_day (
    id BIGSERIAL PRIMARY KEY,
    training_plan_id BIGINT NOT NULL REFERENCES training_plan(id) ON DELETE CASCADE,
    day_number INT NOT NULL,
    name VARCHAR(100),
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_training_plan_day UNIQUE(training_plan_id, day_number)
);

CREATE TABLE training_plan_exercise (
    id BIGSERIAL PRIMARY KEY,
    training_plan_day_id BIGINT NOT NULL REFERENCES training_plan_day(id) ON DELETE CASCADE,
    exercise_id BIGINT NOT NULL REFERENCES exercise(id),
    sequence INT NOT NULL,
    target_sets INT,
    target_reps_min INT,
    target_reps_max INT,
    target_rpe NUMERIC(3,1),
    rest_seconds INT,
    notes VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_training_plan_exercise_sequence UNIQUE(training_plan_day_id, sequence)
);

CREATE TABLE workout (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    training_plan_id BIGINT REFERENCES training_plan(id) ON DELETE SET NULL,
    training_plan_day_id BIGINT REFERENCES training_plan_day(id) ON DELETE SET NULL,
    name VARCHAR(100),
    status VARCHAR(20) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    duration_seconds INT,
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_workout_user_time ON workout(user_id, started_at DESC);

CREATE TABLE workout_exercise (
    id BIGSERIAL PRIMARY KEY,
    workout_id BIGINT NOT NULL REFERENCES workout(id) ON DELETE CASCADE,
    exercise_id BIGINT NOT NULL REFERENCES exercise(id),
    sequence INT NOT NULL,
    exercise_name VARCHAR(100) NOT NULL,
    target_sets INT,
    target_reps_min INT,
    target_reps_max INT,
    target_rpe NUMERIC(3,1),
    rest_seconds INT,
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_workout_exercise_sequence UNIQUE(workout_id, sequence)
);
CREATE INDEX idx_workout_exercise_workout ON workout_exercise(workout_id);

CREATE TABLE workout_set (
    id BIGSERIAL PRIMARY KEY,
    workout_exercise_id BIGINT NOT NULL REFERENCES workout_exercise(id) ON DELETE CASCADE,
    set_number INT NOT NULL,
    weight_kg NUMERIC(7,2),
    reps INT,
    rpe NUMERIC(3,1),
    rir NUMERIC(3,1),
    is_warmup BOOLEAN NOT NULL DEFAULT FALSE,
    is_failure BOOLEAN NOT NULL DEFAULT FALSE,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_workout_set_number UNIQUE(workout_exercise_id, set_number)
);

CREATE TABLE personal_record (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    exercise_id BIGINT NOT NULL REFERENCES exercise(id),
    record_type VARCHAR(30) NOT NULL,
    weight_kg NUMERIC(7,2),
    reps INT,
    estimated_1rm NUMERIC(7,2),
    workout_id BIGINT REFERENCES workout(id) ON DELETE SET NULL,
    workout_set_id BIGINT REFERENCES workout_set(id) ON DELETE SET NULL,
    achieved_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_pr_source_type UNIQUE(workout_set_id, record_type)
);
CREATE INDEX idx_pr_user_exercise ON personal_record(user_id, exercise_id, achieved_at DESC);
