import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

// Separate trends per path, because averaging a create against a replay would
// hide the very difference this test exists to show.
const createLatency = new Trend('create_latency', true);
const replayLatency = new Trend('replay_latency', true);

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const VUS = parseInt(__ENV.VUS || '50');
const DURATION = __ENV.DURATION || '30s';

export const options = {
  scenarios: {
    create: {
      executor: 'constant-vus', vus: VUS, duration: DURATION,
      exec: 'createPath', startTime: '0s',
    },
    replay: {
      // Starts after `create` finishes so the two never contend for the same
      // connection pool and skew each other's percentiles.
      executor: 'constant-vus', vus: VUS, duration: DURATION,
      exec: 'replayPath', startTime: DURATION,
    },
  },
  thresholds: {
    create_latency: ['p(99)<250'],
    replay_latency: ['p(99)<150'],
    checks: ['rate>0.99'],
  },
};

function json(token) {
  const h = { 'Content-Type': 'application/json' };
  if (token) h['Authorization'] = `Bearer ${token}`;
  return h;
}

export function setup() {
  const stamp = Date.now();
  const password = 'loadtest-password';
  const senderEmail = `load-sender-${stamp}@example.com`;
  const recipientEmail = `load-recipient-${stamp}@example.com`;

  const recipient = http.post(`${BASE}/auth/signup`, JSON.stringify({
    firstName: 'Load', lastName: 'Recipient', email: recipientEmail, password,
  }), { headers: json() });

  http.post(`${BASE}/auth/signup`, JSON.stringify({
    firstName: 'Load', lastName: 'Sender', email: senderEmail, password,
  }), { headers: json() });

  const login = http.post(`${BASE}/auth/login`, JSON.stringify({
    email: senderEmail, password,
  }), { headers: json() });

  const token = login.json('token');
  const recipientId = recipient.json('id');
  if (!token || !recipientId) {
    throw new Error(`setup failed: signup=${recipient.status} login=${login.status} body=${login.body}`);
  }

  // Prime one key so every replay iteration takes the replay branch.
  const replayKey = `replayfixed${stamp}`;
  const primed = http.post(`${BASE}/transactions`, body(recipientId), {
    headers: { ...json(token), 'Idempotency-Key': replayKey },
  });
  if (primed.status !== 201) {
    throw new Error(`priming the replay key failed: ${primed.status} ${primed.body}`);
  }

  return { token, recipientId, replayKey };
}

function body(recipientId, amount = '15.90') {
  return JSON.stringify({ amount, currencyCode: 'AUD', recipientId });
}

export function createPath(data) {
  // A unique key per iteration, otherwise this would measure replays.
  const key = `c${__VU}x${__ITER}x${Date.now()}`;
  const res = http.post(`${BASE}/transactions`, body(data.recipientId), {
    headers: { ...json(data.token), 'Idempotency-Key': key },
  });
  createLatency.add(res.timings.duration);
  check(res, { 'create returned 201': (r) => r.status === 201 });
}

export function replayPath(data) {
  const res = http.post(`${BASE}/transactions`, body(data.recipientId), {
    headers: { ...json(data.token), 'Idempotency-Key': data.replayKey },
  });
  replayLatency.add(res.timings.duration);
  check(res, {
    'replay returned 201': (r) => r.status === 201,
    'replay was served from the store': (r) => r.headers['Idempotent-Replay'] === 'true',
  });
}
