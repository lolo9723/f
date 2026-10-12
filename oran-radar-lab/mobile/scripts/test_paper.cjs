// Pure barrier logic + real tracker orchestration with native/network adapters.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');
function transpile(file) {
  return ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText;
}
function evaluate(file, requireMock, extras = {}) {
  const module = { exports: {} };
  vm.runInNewContext(transpile(file), { module, exports: module.exports, require: requireMock,
    console, Date, Math, Set, Map, Promise, ...extras }, { filename: file });
  return module.exports;
}
const core = evaluate('src/lib/paperCore.ts', require);
const T = 1800000000000, H = 3600000;
const position = { position_id: 'test', pair: 'SOLTRY', entry: 100, target: 103, stop: 98,
  created_at: T, deadline_at: T + 48 * H, state: 'TRACKING', closed_at: null, target_hit_at: null,
  observed_at: null, observed_price: null, last_trade_id: null,
  gross_return_pct: null, net_return_pct: null, fee_bps: 20, origin: 'device' };
assert.equal(core.applyTrade(position, { a: 1, T: T - 1, p: '104' }).state, 'TRACKING');
const hit = core.applyTrade(position, { a: 1, T: T + 17 * H + 12 * 60000, p: '104' });
assert.equal(hit.target_hit_at, T + 17 * H + 12 * 60000);
assert.ok(Math.abs(hit.net_return_pct - 2.8) < 1e-10);
assert.equal(core.applyTrade(hit, { a: 2, T: T + 18 * H, p: '96' }).state, 'SUCCESS');
const stopped = core.applyTrade(position, { a: 1, T: T + 1000, p: '97' });
assert.equal(core.applyTrade(stopped, { a: 2, T: T + 2000, p: '104' }).state, 'STOPPED');
assert.equal(core.applyTrade(position, { a: 1, T: T + 48 * H, p: '104' }).state, 'TIME_EXPIRED');
assert.throws(() => core.validateLevels(100, 99, null));
assert.throws(() => core.validateLevels(100, 103, NaN));

async function orchestration(gap) {
  const saved = new Map([['paperPositions.v1', JSON.stringify([position])]]);
  let state, requested = [], now = T + 20000;
  const intervals = [], appState = { currentState: 'active', addEventListener() {} };
  class FakeSocket { constructor() { this.readyState = 1; } close() {} send() {} }
  const react = { useState: () => [state, next => state = next], useEffect: fn => fn() };
  const api = { requestJSON: async url => {
    requested.push(url);
    if (url.includes('startTime=')) return gap ? [{ a: 1, T: T + 1000, p: '100' }, { a: 3, T: T + 3000, p: '104' }] :
      [{ a: 1, T: T + 1000, p: '100' }, { a: 2, T: T + 2000, p: '104' }, { a: 3, T: T + 3000, p: '101' }];
    return [];
  } };
  const storage = { getItem: async key => saved.get(key) ?? null, setItem: async (key, value) => saved.set(key, value) };
  const tracker = evaluate('src/lib/paperTracker.ts', name => {
    if (name === 'react') return react;
    if (name === 'react-native') return { AppState: appState };
    if (name === '@react-native-async-storage/async-storage') return storage;
    if (name === './api') return api;
    if (name === './paperCore') return core;
    throw Error(name);
  }, { WebSocket: FakeSocket, setInterval: fn => intervals.push(fn),
    Date: class extends Date { static now() { return now; } } });
  tracker.usePaperTracker();
  for (let i = 0; i < 10; i++) await new Promise(resolve => setImmediate(resolve));
  intervals.forEach(fn => fn());
  for (let i = 0; i < 20; i++) await new Promise(resolve => setImmediate(resolve));
  assert.ok(requested[0].includes('startTime='));
  const result = JSON.parse(saved.get('paperPositions.v1'))[0];
  if (gap) {
    assert.equal(result.state, 'TRACKING'); assert.equal(result.feed_blocked, true);
    assert.equal(result.target_hit_at, null); assert.ok(state.error);
  } else {
    assert.equal(result.state, 'SUCCESS'); assert.equal(result.target_hit_at, T + 2000);
    assert.equal(result.last_trade_id, 2); // Later retracement never changes first hit.
  }
}
(async () => {
  await orchestration(false); await orchestration(true);
  console.log('Paper tests passed: exact hit time, stop order, expiry, invalid levels, restart backfill and gap withholding.');
})().catch(error => { console.error(error); process.exitCode = 1; });
