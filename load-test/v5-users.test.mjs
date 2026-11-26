import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import vm from 'node:vm';

// Run the actual k6 modules in isolated VU contexts against one stateful API stub.
async function loadVu(id, http, metrics = []) {
  const context = vm.createContext({
    __ENV: { BASE_URL: 'http://test' }, __VU: id, __ITER: 0,
    Math: Object.assign(Object.create(Math), { random: () => 0.8 }),
  });
  const sources = new Map();
  const stubs = {
    'k6/http': { default: http },
    k6: {
      check: (value, checks) => Object.values(checks).every((fn) => fn(value)),
      fail: (message) => { throw new Error(message); }, sleep: () => {},
    },
    'k6/execution': { vu: { idInTest: id } },
    'k6/metrics': Object.fromEntries(['Counter', 'Rate', 'Trend'].map((name) => [name,
      class { constructor(metric) { this.metric = metric; }
        add(value) { metrics.push([this.metric, value]); } },
    ])),
  };
  async function moduleFor(name) {
    if (sources.has(name)) return sources.get(name);
    const mod = name in stubs
      ? new vm.SyntheticModule(Object.keys(stubs[name]), function () {
        for (const [key, value] of Object.entries(stubs[name])) this.setExport(key, value);
      }, { context })
      : new vm.SourceTextModule(await readFile(new URL(name, import.meta.url), 'utf8'), { context });
    sources.set(name, mod);
    return mod;
  }
  const root = await moduleFor('./v5-common.js');
  await root.link(moduleFor);
  await root.evaluate();
  return { run: root.namespace.runMixedIteration, setup: root.namespace.setupEnvironment,
    select: sources.get('./v5-users.js').namespace.userForVu };
}

function api() {
  const users = new Map();
  const plans = new Map();
  const active = new Map();
  const calls = [];
  let afterCreate;
  let nextId = 1;
  const response = (status, data) => ({ status, json: () => data, timings: { duration: 1 } });
  return {
    users, plans, active, calls,
    overlap(fn) { afterCreate = fn; },
    post(url, raw, params) {
      const path = new URL(url).pathname;
      const data = raw && JSON.parse(raw);
      const token = params.headers.Authorization?.replace('Bearer ', '');
      calls.push({ path, token });
      if (path.endsWith('/auth/register')) {
        assert.ok(!users.has(data.username));
        users.set(data.username, `token-${data.username}`);
        return response(201, {});
      }
      if (path.endsWith('/auth/login')) return response(200, { accessToken: users.get(data.username) });
      assert.ok([...users.values()].includes(token), 'request must use a provisioned identity');
      if (path === '/api/v1/training-plans') {
        const id = nextId++;
        plans.set(id, token);
        return response(201, { id, days: [{ id: id * 10 }] });
      }
      if (path.endsWith('/activate')) {
        assert.equal(plans.get(Number(path.split('/')[4])), token);
        return response(200, {});
      }
      if (path === '/api/v1/workouts') {
        assert.equal(plans.get(data.trainingPlanId), token, 'plan must belong to this VU');
        if (active.has(token)) return response(409, {});
        const id = nextId++;
        active.set(token, id);
        const hook = afterCreate;
        afterCreate = null;
        hook?.();
        return response(201, { id, exercises: [{ id: 1 }] });
      }
      assert.equal(Number(path.split('/')[4]), active.get(token));
      if (path.endsWith('/sets')) return response(201, {});
      if (path.endsWith('/complete')) {
        active.delete(token);
        return response(200, {});
      }
      throw new Error(`unexpected request ${path}`);
    },
  };
}

test('setup provisions a unique identity and active plan for every possible VU', async () => {
  const server = api();
  const metrics = [];
  const vu = await loadVu(1, server, metrics);
  const environment = vu.setup(4);
  assert.equal(server.users.size, 4);
  assert.equal(new Set(environment.users.map((user) => user.token)).size, 4);
  assert.equal(server.plans.size, 4);
  assert.equal(metrics.length, 0, 'provisioning must not count as business traffic');
  assert.equal(vu.select(environment, 4).token, environment.users[3].token);
  assert.throws(() => vu.select(environment, 5), /no dedicated account/);
  assert.throws(() => vu.select(environment, 0), /no dedicated account/);
  assert.throws(() => vu.setup(0), /positive integer/);
});

test('overlapping VUs complete workouts without shared-account 409 conflicts', async () => {
  const server = api();
  const metrics = [];
  const first = await loadVu(1, server, metrics);
  const last = await loadVu(4, server, metrics);
  const environment = first.setup(4);
  server.overlap(() => last.run(environment));
  first.run(environment);
  first.run(environment);
  assert.equal(server.active.size, 0);
  assert.equal(server.calls.filter((call) => call.path.endsWith('/complete')).length, 3);
  assert.ok(metrics.filter(([name]) => name === 'business_success').every(([, value]) => value));
});

test('setup retries auth throttling and stops on permanent failures', async () => {
  const server = api();
  let attempts = 0;
  const original = server.post.bind(server);
  server.post = (...args) => ++attempts === 1 ? { status: 429 } : original(...args);
  const vu = await loadVu(1, server);
  assert.equal(vu.setup(1).users.length, 1);
  server.post = () => ({ status: 500 });
  assert.throws(() => vu.setup(1), /setup failed/);
});
