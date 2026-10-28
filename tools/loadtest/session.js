// RideMate load test (architecture §4.12): k6 at 100 concurrent sessions against the stub model provider.
//
// Each virtual user loops over realistic phone sessions:
//   config-check → 0–1 × place-pick (voice destination) → 4 × read-screen (one per ride app) → 1 × reread
//   → 2 × parse-notification → 1 × event
// with user think time between taps. Every session is a separate user (its own X-Session-Id).
//
// Read images: the bundled demo screens (android/app/src/main/assets/demo) when present, else a generated tiny PNG.
// DEMO_READS (default 1) of the 4 reads per session send an untouched demo PNG (the allowlisted demo path: hot-store
// cache, live model only on a miss); the other reads send a demo PNG made unique with a PNG tEXt chunk, so they take
// the live path (quota → spend reservation → router → stub → validation) like a user's own screenshot.
//
//   k6 run tools/loadtest/session.js                               (BASE_URL, VUS, DURATION, DEMO_READS, THINK_S)
//   k6 inspect tools/loadtest/session.js                           (validate only)
//
// SCENARIO=live_reads runs the separate, metered "20 live reads" step against a deployed backend instead
// (tools/loadtest/live_reads.md): it needs BASE_URL (https), TOKEN (a Firebase ID token) and CONFIRM_SPEND=yes.

import http from 'k6/http';
import exec from 'k6/execution';
import { check, group, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'http://127.0.0.1:8787').replace(/\/+$/, '');
const VUS = parseInt(__ENV.VUS || '100', 10);
const DURATION = __ENV.DURATION || '2m';
const DEMO_READS = Math.max(0, Math.min(4, parseInt(__ENV.DEMO_READS || '1', 10)));
const THINK_S = parseFloat(__ENV.THINK_S || '1');  // scales every think time; 0 = no pauses
const APP_VERSION = '1';
const DEMO_DIR = __ENV.DEMO_DIR || '../../android/app/src/main/assets/demo';
const SCENARIO = __ENV.SCENARIO || 'sessions';
const LIVE = SCENARIO === 'live_reads';
const LIVE_READS = parseInt(__ENV.LIVE_READS || '20', 10);
const TOKEN = LIVE ? __ENV.TOKEN || '' : 'loadtest';

const SESSION_OPTIONS = {
  scenarios: {
    sessions: { executor: 'constant-vus', vus: VUS, duration: DURATION, gracefulStop: '60s' },
  },
  thresholds: {
    // SLO (architecture §4.12): read-screen server p95 ≤ 8 s; error rate < 1 %.
    'http_req_duration{endpoint:read_screen}': ['p(95)<=8000'],
    read_server_latency_ms: ['p(95)<=8000'], // the backend's own `latency_ms` for each read
    http_req_failed: ['rate<0.01'],
    // The other endpoints must finish inside the phone's timeouts (server deadline + 5 s, §4.12).
    'http_req_duration{endpoint:reread}': ['p(95)<=20000'],
    'http_req_duration{endpoint:parse_notification}': ['p(95)<=11000'],
    'http_req_duration{endpoint:place_pick}': ['p(95)<=11000'],
    'http_req_duration{endpoint:config_check}': ['p(95)<=3000'],
    'http_req_duration{endpoint:event}': ['p(95)<=3000'],
    'http_req_failed{endpoint:read_screen}': ['rate<0.01'],
    'http_req_duration{endpoint:read_screen,path:live}': ['p(95)<=8000'],
    'http_req_duration{endpoint:read_screen,path:demo}': ['p(95)<=8000'],
    // Always-true thresholds: they only make k6 report these sub-metrics in the summary.
    'read_source{source:live}': ['count>=0'],
    'read_source{source:cache}': ['count>=0'],
    'read_source{source:recorded}': ['count>=0'],
    checks: ['rate>0.99'],
    session_completed: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// The metered live step: LIVE_READS reads, two at a time, through the real model on a deployed backend.
const LIVE_OPTIONS = {
  scenarios: {
    live_reads: {
      executor: 'shared-iterations', vus: 2, iterations: LIVE_READS, maxDuration: '5m', exec: 'liveRead',
    },
  },
  thresholds: {
    read_server_latency_ms: ['p(95)<=8000'], // server-side latency reported by the backend
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
    'read_source{source:live}': ['count>=0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export const options = LIVE ? LIVE_OPTIONS : SESSION_OPTIONS;

const serverLatency = new Trend('read_server_latency_ms', true);
const readSource = new Counter('read_source');
const sessionCompleted = new Rate('session_completed');

// ---- images ---------------------------------------------------------------------------------------------------

const DEMO_SCREENS = [
  { file: 'kestrel/fare_options.png', app: 'demo_kestrel' },
  { file: 'pepper/fare_options.png', app: 'demo_pepper' },
  { file: 'tamarind/fare_options.png', app: 'demo_tamarind' },
];

function tryOpen(path) {
  try {
    return open(path, 'b');
  } catch (e) {
    return null;
  }
}

const DEMO_IMAGES = DEMO_SCREENS.map((s) => ({ app: s.app, bytes: tryOpen(`${DEMO_DIR}/${s.file}`) })).filter(
  (s) => s.bytes !== null,
);

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(bytes) {
  let c = 0xffffffff;
  for (let i = 0; i < bytes.length; i++) c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function u32(n) {
  return [(n >>> 24) & 0xff, (n >>> 16) & 0xff, (n >>> 8) & 0xff, n & 0xff];
}

function ascii(s) {
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i) & 0xff;
  return out;
}

function concat(parts) {
  const total = parts.reduce((n, p) => n + p.length, 0);
  const out = new Uint8Array(total);
  let off = 0;
  for (const p of parts) {
    out.set(p, off);
    off += p.length;
  }
  return out;
}

function chunk(type, data) {
  const body = concat([ascii(type), data]);
  return concat([new Uint8Array(u32(data.length)), body, new Uint8Array(u32(crc32(body)))]);
}

const PNG_SIGNATURE = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

function adler32(bytes) {
  let a = 1;
  let b = 0;
  for (let i = 0; i < bytes.length; i++) {
    a = (a + bytes[i]) % 65521;
    b = (b + a) % 65521;
  }
  return ((b << 16) | a) >>> 0;
}

// A valid w×h RGB PNG (zlib "stored" blocks, no compression library needed) carrying a unique tEXt chunk.
function generatedPng(tag, w = 8, h = 8) {
  const raw = new Uint8Array(h * (1 + 3 * w));
  for (let y = 0; y < h; y++) {
    raw[y * (1 + 3 * w)] = 0; // filter: none
    for (let x = 0; x < w; x++) {
      const o = y * (1 + 3 * w) + 1 + 3 * x;
      raw[o] = (x * 32) & 0xff;
      raw[o + 1] = (y * 32) & 0xff;
      raw[o + 2] = 0x80;
    }
  }
  const len = raw.length; // < 65535: one final stored block
  const zlib = concat([
    new Uint8Array([0x78, 0x01, 0x01, len & 0xff, (len >>> 8) & 0xff, ~len & 0xff, (~len >>> 8) & 0xff]),
    raw,
    new Uint8Array(u32(adler32(raw))),
  ]);
  const ihdr = concat([new Uint8Array(u32(w)), new Uint8Array(u32(h)), new Uint8Array([8, 2, 0, 0, 0])]);
  return concat([
    PNG_SIGNATURE,
    chunk('IHDR', ihdr),
    chunk('tEXt', ascii(`Comment\u0000loadtest ${tag}`)),
    chunk('IDAT', zlib),
    chunk('IEND', new Uint8Array(0)),
  ]).buffer;
}

// The same picture with a unique tEXt chunk inserted before IEND: still a valid PNG, new SHA-256.
function uniquePng(buffer, tag) {
  const bytes = new Uint8Array(buffer);
  const iend = bytes.length - 12;
  return concat([bytes.subarray(0, iend), chunk('tEXt', ascii(`Comment\u0000loadtest ${tag}`)), bytes.subarray(iend)])
    .buffer;
}

// ---- helpers --------------------------------------------------------------------------------------------------

function uuid() {
  return crypto.randomUUID(); // WebCrypto global in k6
}

function think(minS, maxS) {
  if (THINK_S > 0) sleep(THINK_S * (minS + Math.random() * (maxS - minS)));
}

function headers(session, extra) {
  return Object.assign(
    {
      Authorization: `Bearer ${TOKEN}`,
      'X-Request-Id': uuid(),
      'X-Session-Id': session,
      'X-App-Version': APP_VERSION,
    },
    extra || {},
  );
}

function ok(res, endpoint, statuses) {
  return check(res, { [`${endpoint}: status ${statuses.join('/')}`]: (r) => statuses.includes(r.status) });
}

const LIVE_APPS = ['uber', 'ola', 'rapido'];

function readScreen(session, n, useDemo) {
  let bytes;
  let meta;
  if (useDemo && DEMO_IMAGES.length > 0) {
    const demo = DEMO_IMAGES[n % DEMO_IMAGES.length];
    bytes = demo.bytes;
    meta = { app: demo.app, frame_w: 1080, frame_h: 2400, demo: true };
  } else {
    const tag = `${session}-${n}`;
    bytes = DEMO_IMAGES.length > 0 ? uniquePng(DEMO_IMAGES[n % DEMO_IMAGES.length].bytes, tag) : generatedPng(tag);
    meta = { app: LIVE_APPS[n % LIVE_APPS.length], frame_w: 1080, frame_h: 2400, demo: false };
  }
  const body = { meta: JSON.stringify(meta), image: http.file(bytes, 'screen.png', 'image/png') };
  const res = http.post(`${BASE_URL}/v1/read-screen`, body, {
    headers: headers(session, { 'X-Tap-Deadline-Ms': '20000' }),
    tags: { endpoint: 'read_screen', name: 'read-screen', path: useDemo ? 'demo' : 'live' },
  });
  const good = ok(res, 'read-screen', [200]);
  if (good) {
    const json = res.json();
    check(json, { 'read-screen: has a read with options': (j) => j && j.read && Array.isArray(j.read.options) });
    if (typeof json.latency_ms === 'number') serverLatency.add(json.latency_ms);
    readSource.add(1, { source: String(json.source) });
  }
  return good;
}

function reread(session) {
  const rows = ['r1', 'r2'];
  const body = { meta: JSON.stringify({ app: 'ola', rows: rows.map((row_id) => ({ row_id })) }) };
  for (const row of rows) body[`crop_${row}`] = http.file(generatedPng(`${session}-${row}`, 16, 4), `${row}.png`, 'image/png');
  const res = http.post(`${BASE_URL}/v1/reread`, body, {
    headers: headers(session, { 'X-Tap-Deadline-Ms': '20000' }),
    tags: { endpoint: 'reread', name: 'reread' },
  });
  return ok(res, 'reread', [200]) && check(res.json(), { 'reread: rows': (j) => Array.isArray(j.rows) });
}

const NOTIFICATIONS = [
  { app: 'rapido', title: 'Rapido', text: 'Captain <m> is on the way, arriving in 4 min', leg_state: 'SEARCHING' },
  { app: 'uber', title: 'Uber', text: 'Looking for a driver near you', leg_state: 'BOOK_REQUESTED' },
  { app: 'ola', title: 'Ola', text: 'Your driver cancelled. Finding you another ride', leg_state: 'ASSIGNED' },
];

function parseNotification(session, n) {
  const note = NOTIFICATIONS[n % NOTIFICATIONS.length];
  const payload = {
    app: note.app,
    version_code: 1234,
    title: note.title,
    text: note.text,
    channel_id: 'ride_updates',
    category: 'status',
    is_ongoing: true,
    leg_state: note.leg_state,
  };
  const res = http.post(`${BASE_URL}/v1/parse-notification`, JSON.stringify(payload), {
    headers: headers(session, { 'Content-Type': 'application/json' }),
    tags: { endpoint: 'parse_notification', name: 'parse-notification' },
  });
  return ok(res, 'parse-notification', [200]) && check(res.json(), { 'parse: label': (j) => typeof j.label === 'string' });
}

function placePick(session) {
  const payload = {
    transcript: 'airport',
    lang: 'en-IN',
    candidates: [
      { id: 'c1', label: 'Paltan Bazaar, Guwahati' },
      { id: 'c2', label: 'LGBI Airport, Guwahati' },
    ],
  };
  const res = http.post(`${BASE_URL}/v1/place-pick`, JSON.stringify(payload), {
    headers: headers(session, { 'Content-Type': 'application/json' }),
    tags: { endpoint: 'place_pick', name: 'place-pick' },
  });
  return ok(res, 'place-pick', [200]) && check(res.json(), { 'place-pick: pick': (j) => typeof j.pick === 'string' });
}

function configCheck(session) {
  const res = http.get(`${BASE_URL}/v1/config-check`, {
    headers: headers(session),
    tags: { endpoint: 'config_check', name: 'config-check' },
  });
  return ok(res, 'config-check', [200]) && check(res.json(), { 'config-check: ai_enabled': (j) => j.ai_enabled === true });
}

function event(session, reads) {
  const payload = {
    session_id: session,
    app: 'uber',
    version_code: 1234,
    outcome: 'confirmed_all',
    time_to_confirm_bucket: '1_3m',
    android_version: 34,
    reads,
    verified_rows: 9,
    unreadable_rows: 1,
    rule_hits: 6,
    rule_misses: 1,
    misses_unclassified: 0,
    demo: false,
  };
  const res = http.post(`${BASE_URL}/v1/event`, JSON.stringify(payload), {
    headers: headers(session, { 'Content-Type': 'application/json' }),
    tags: { endpoint: 'event', name: 'event' },
  });
  return ok(res, 'event', [202]);
}

// ---- the session ----------------------------------------------------------------------------------------------

export function setup() {
  if (LIVE) {
    if (__ENV.CONFIRM_SPEND !== 'yes') throw new Error('live_reads spends Modal credit: pass -e CONFIRM_SPEND=yes');
    if (!/^https:\/\//.test(BASE_URL)) throw new Error('live_reads needs the deployed https BASE_URL');
    if (!TOKEN) throw new Error('live_reads needs -e TOKEN=<Firebase ID token of a Google-signed-in tester>');
  }
  const res = http.get(`${BASE_URL}/healthz`, { tags: { endpoint: 'healthz', name: 'healthz' } });
  if (res.status !== 200) throw new Error(`backend not healthy at ${BASE_URL} (status ${res.status})`);
  return { demoImages: DEMO_IMAGES.length };
}

export default function () {
  const session = uuid();
  let allOk = true;
  group('compare', () => {
    allOk = configCheck(session) && allOk;
    if (Math.random() < 0.5) {
      think(2, 4); // says the destination aloud
      allOk = placePick(session) && allOk;
    }
    think(1, 3); // confirms the address, picks apps and vehicle types
    for (let n = 0; n < 4; n++) {
      allOk = readScreen(session, n, n >= 4 - DEMO_READS) && allOk;
      think(2, 5); // switch to the next ride app and tap Read fares
    }
    allOk = reread(session) && allOk;
  });
  group('guardian', () => {
    for (let n = 0; n < 2; n++) {
      think(1, 3);
      allOk = parseNotification(session, n) && allOk;
    }
  });
  think(1, 2);
  allOk = event(session, 4) && allOk;
  sessionCompleted.add(allOk);
  think(2, 4); // before the next session
}

// One metered live read per iteration: a unique (never allowlisted) screenshot, so it always reaches the model.
export function liveRead() {
  const n = exec.scenario.iterationInTest;
  readScreen(uuid(), n, false);
  sleep(1);
}
