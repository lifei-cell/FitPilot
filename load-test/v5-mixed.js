import { runMixedIteration, setupEnvironment } from './v5-common.js';

export const options = {
  scenarios: {
    mixed_traffic: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 50),
      timeUnit: '1s',
      duration: __ENV.DURATION || '30m',
      preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || 20),
      maxVUs: Number(__ENV.MAX_VUS || 100),
      gracefulStop: '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    ordinary_api_duration: ['p(95)<250'],
    agent_duration: ['p(95)<8000'],
    business_success: ['rate>0.99'],
  },
};

export function setup() {
  return setupEnvironment();
}

export default function (data) {
  runMixedIteration(data);
}
