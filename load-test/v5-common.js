import http from 'k6/http';
import { check } from 'k6';
import { vu } from 'k6/execution';
import { userForVu } from './v5-users.js';
import { planPayload } from './v5-plan.js';
export { setupEnvironment } from './v5-users.js';
import { Counter, Rate, Trend } from 'k6/metrics';

export const ordinaryApiDuration = new Trend('ordinary_api_duration', true);
export const agentDuration = new Trend('agent_duration', true);
export const businessSuccess = new Rate('business_success');
export const readOperations = new Counter('read_operations');
export const writeOperations = new Counter('write_operations');
export const agentOperations = new Counter('agent_operations');

let agentSessionId;

function jsonHeaders(token) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  return { headers };
}

function body(response) {
  try {
    return response.json('data');
  } catch (_) {
    return null;
  }
}

function accepted(response, expected, name, metric) {
  if (metric) metric.add(response.timings.duration);
  const ok = check(response, { [`${name} status`]: (r) => expected.includes(r.status) });
  businessSuccess.add(ok);
  return ok;
}

export function runMixedIteration(environment) {
  const data = userForVu(environment, vu.idInTest);
  const roll = Math.random();
  if (roll < 0.70) {
    readOperations.add(1);
    return ordinaryRead(data);
  }
  if (roll < 0.90) {
    writeOperations.add(1);
    return writeFlow(data);
  }
  agentOperations.add(1);
  return agentFlow(data);
}

function ordinaryRead(data) {
  const paths = [
    '/api/v1/exercises/1',
    '/api/v1/training-plans/active/current',
    '/api/v1/workouts?page=1&size=20',
    '/api/v1/agent/preferences',
  ];
  const path = paths[Math.floor(Math.random() * paths.length)];
  const response = http.get(`${data.baseUrl}${path}`, {
    ...jsonHeaders(data.token),
    tags: { name: `GET ${path.split('?')[0]}`, traffic: 'ordinary', operation: 'read' },
  });
  accepted(response, [200], 'ordinary read', ordinaryApiDuration);
}

function writeFlow(data) {
  if (Math.random() < 0.20) return createDraftPlan(data);

  const workout = http.post(`${data.baseUrl}/api/v1/workouts`, JSON.stringify({
    trainingPlanId: data.planId,
    trainingPlanDayId: data.planDayId,
    name: `Load workout ${__VU}-${__ITER}`,
  }), { ...jsonHeaders(data.token), tags: {
    name: 'POST /api/v1/workouts', traffic: 'ordinary', operation: 'workout-create',
  } });
  accepted(workout, [201], 'create workout', ordinaryApiDuration);
  const workoutData = body(workout);
  if (!workoutData?.id || !workoutData.exercises?.[0]?.id) return;

  const set = http.post(
    `${data.baseUrl}/api/v1/workouts/${workoutData.id}/exercises/${workoutData.exercises[0].id}/sets`,
    JSON.stringify({ weightKg: 60, reps: 8, rpe: 8, rir: 2, isWarmup: false, isFailure: false }),
    { ...jsonHeaders(data.token), tags: {
      name: 'POST /api/v1/workouts/{id}/exercises/{exerciseId}/sets',
      traffic: 'ordinary', operation: 'workout-set',
    } },
  );
  if (!accepted(set, [201], 'add workout set', ordinaryApiDuration)) return;

  const complete = http.post(`${data.baseUrl}/api/v1/workouts/${workoutData.id}/complete`, null, {
    ...jsonHeaders(data.token),
    tags: { name: 'POST /api/v1/workouts/{id}/complete', traffic: 'ordinary', operation: 'workout-complete' },
  });
  accepted(complete, [200], 'complete workout', ordinaryApiDuration);
}

function createDraftPlan(data) {
  const response = http.post(
    `${data.baseUrl}/api/v1/training-plans`,
    planPayload(`Load draft ${data.runId}-${__VU}-${__ITER}`),
    { ...jsonHeaders(data.token), tags: {
      name: 'POST /api/v1/training-plans', traffic: 'ordinary', operation: 'plan-write',
    } },
  );
  accepted(response, [201], 'create draft plan', ordinaryApiDuration);
}

function agentFlow(data) {
  if (!agentSessionId) {
    const session = http.post(`${data.baseUrl}/api/v1/agent/sessions`, null, {
      ...jsonHeaders(data.token),
      tags: { name: 'POST /api/v1/agent/sessions', traffic: 'agent', operation: 'agent-session' },
    });
    accepted(session, [201], 'create agent session', agentDuration);
    agentSessionId = body(session)?.id;
    if (!agentSessionId) return;
  }

  const response = http.post(
    `${data.baseUrl}/api/v1/agent/sessions/${agentSessionId}/messages`,
    JSON.stringify({ message: '查看我的当前训练计划' }),
    { ...jsonHeaders(data.token), tags: {
      name: 'POST /api/v1/agent/sessions/{id}/messages', traffic: 'agent', operation: 'agent-message',
    } },
  );
  accepted(response, [200], 'agent message', agentDuration);
}
