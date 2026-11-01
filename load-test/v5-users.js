import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { planPayload } from './v5-plan.js';

function setupPost(baseUrl, path, payload, token, expected) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  // Setup has its own tags and never contributes to business_success.
  const params = { headers, tags: { phase: 'setup', name: `POST ${path}` } };
  let response;
  for (let attempt = 0; attempt < 5; attempt++) {
    response = http.post(`${baseUrl}${path}`, payload, params);
    if (response.status !== 429) break;
    sleep(1);
  }
  if (!check(response, { [`setup ${path}`]: (r) => r.status === expected })) {
    fail(`account setup failed: ${path} returned ${response.status}`);
  }
  return response.json('data');
}

export function setupEnvironment(maxVUs) {
  if (!Number.isSafeInteger(maxVUs) || maxVUs < 1) {
    fail('MAX_VUS must be a positive integer');
  }
  const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8080';
  const runId = `${Date.now()}${Math.floor(Math.random() * 100000)}`;
  const users = [];
  for (let index = 0; index < maxVUs; index++) {
    // Two auth requests per user, paced below the default 5 requests/s limit.
    if (index > 0) sleep(0.5);
    const username = `v5load_${runId}_${index + 1}`;
    const password = `FitPilot!${runId}_${index + 1}`;
    setupPost(baseUrl, '/api/v1/auth/register', JSON.stringify({
      username, email: `${username}@example.invalid`, password,
    }), null, 201);
    const login = setupPost(baseUrl, '/api/v1/auth/login',
      JSON.stringify({ username, password }), null, 200);
    if (!login?.accessToken) fail('login response did not contain an access token');
    const token = login.accessToken;
    const plan = setupPost(baseUrl, '/api/v1/training-plans',
      planPayload(`V5 active ${runId}-${index + 1}`), token, 201);
    if (!plan?.id || !plan.days?.[0]?.id) fail('setup plan response is incomplete');
    setupPost(baseUrl, `/api/v1/training-plans/${plan.id}/activate`, null, token, 200);
    users.push({ token, planId: plan.id, planDayId: plan.days[0].id });
  }
  return { baseUrl, runId, users };
}

export function userForVu(environment, id) {
  // Never wrap with modulo: dynamically allocated VUs must not share accounts.
  const user = Number.isSafeInteger(id) && id > 0 ? environment.users[id - 1] : null;
  if (!user) fail(`no dedicated account for VU ${id}; provision the full MAX_VUS pool`);
  return { baseUrl: environment.baseUrl, runId: environment.runId, ...user };
}
