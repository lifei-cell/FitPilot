import { runMixedIteration, setupEnvironment } from './v5-common.js';

const startRate = Number(__ENV.START_RATE || 20);
const peakRate = Number(__ENV.PEAK_RATE || 200);

export const options = {
  scenarios: {
    five_minute_burst: {
      executor: 'ramping-arrival-rate',
      startRate,
      timeUnit: '1s',
      preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || 50),
      maxVUs: Number(__ENV.MAX_VUS || 400),
      stages: [
        { target: peakRate, duration: '1m' },
        { target: peakRate, duration: '3m' },
        { target: startRate, duration: '1m' },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    ordinary_api_duration: ['p(95)<250'],
    agent_duration: ['p(95)<8000'],
    business_success: ['rate>0.99'],
    dropped_iterations: ['count==0'],
  },
};

export function setup() {
  return setupEnvironment();
}

export default function (data) {
  runMixedIteration(data);
}
