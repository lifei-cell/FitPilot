import http from 'k6/http';
import { check } from 'k6';

const rate = Number(__ENV.RATE || 100);
const preAllocated = Math.max(20, Math.ceil(rate / 10));

export const options = {
  discardResponseBodies: true,
  scenarios: {
    hot_exercise_read: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration: __ENV.DURATION || '15s',
      preAllocatedVUs: preAllocated,
      maxVUs: Math.max(100, rate),
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<250'],
  },
};

export function setup() {
  const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8080';
  const warmup = http.get(`${baseUrl}/api/v1/exercises/1`);
  check(warmup, { 'warmup status is 200': (response) => response.status === 200 });
  return { baseUrl };
}

export default function (data) {
  const response = http.get(`${data.baseUrl}/api/v1/exercises/1`, {
    tags: { endpoint: 'exercise-detail-hot' },
  });
  check(response, { 'status is 200': (result) => result.status === 200 });
}
