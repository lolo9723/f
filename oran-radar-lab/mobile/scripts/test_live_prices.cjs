const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');
let hooks = [], index = 0, effects = [], intervals = [], callbacks = [], response, fail = false, calls = [];
const appState = { currentState: 'active', addEventListener: (name, callback) => { callbacks.push(callback); return { remove: () => { callbacks = callbacks.filter(c => c !== callback); } }; } };
const React = {
  useState: value => { const at = index++; if (!(at in hooks)) hooks[at] = value; return [hooks[at], next => { hooks[at] = next; }]; },
  useEffect: effect => effects.push(effect),
};
const native = { prices: async visible => { calls.push(visible); if (fail) throw Error('offline'); return JSON.stringify(response); } };
const source = ts.transpileModule(fs.readFileSync('src/lib/livePrices.ts','utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
const moduleRef = { exports: {} };
let now = 5000;
const mockDate = class extends Date { static now() { return now; } };
vm.runInNewContext(source, { module: moduleRef, exports: moduleRef.exports, Date: mockDate, Set, JSON,
  setInterval: callback => { intervals.push(callback); return callback; }, clearInterval: callback => { intervals = intervals.filter(c => c !== callback); },
  require: name => ({ react: React, 'react-native': { Platform: { OS: 'android' }, AppState: appState },
    'expo-modules-core': { requireOptionalNativeModule: () => native }, '../lib/paperDraft': {}, './paperDraft': { marketPlan: (plan,pair,price) => plan && price <= plan.entry ? { ...plan, status:'STALE' } : plan } })[name] });
const prices = moduleRef.exports;
const quote = (price,at) => ({ price, observed_at:at, change:.02, quote_volume:1000, source:'BINANCE_TR_WS' });
const raw = { pair:'BTCTRY', price:100, price_observed_at:1000, observed_at:500, rally_score:72, bars:120, trade_plan:{ entry:98,stop:95,target:104 } };
const feed = { protocol:'native-binance-tr-prices-v1', state:'LIVE', as_of:5000, tickers:{ BTCTRY:quote(102,5000) } };
let merged = prices.withLivePrice(raw,feed,5000);
assert.equal(merged.price,102); assert.equal(merged.observed_at,500); assert.equal(merged.rally_score,72); assert.equal(merged.bars,120);
assert.equal(raw.price,100); assert.equal(merged.trade_plan.entry,98); assert.equal(merged.trade_plan.stop,95);
assert.equal(prices.withLivePrice(raw,{ ...feed,tickers:{BTCTRY:quote(96,5000)} },5000).trade_plan.status,'STALE');
for (const bad of [quote(0,5000), quote(NaN,5000), quote(103,5001),{...quote(103,5000),source:'OTHER'}]) assert.equal(prices.withLivePrice(raw,{...feed,tickers:{BTCTRY:bad}},5000).price,100);
assert.match(prices.priceAge(5000,5000),/Güncel fiyat/); assert.match(prices.priceAge(5000,36000),/Son fiyat/);
assert.match(prices.priceAge(5001,5000),/doğrulanmadı/);
const flush = () => new Promise(setImmediate);
(async () => {
  response=feed;
  prices.useLivePrices(true); const stop=effects[0](); await flush();
  assert.equal(hooks[0].tickers.BTCTRY.price,102); assert.equal(intervals.length,1);
  response={...feed,tickers:{BTCTRY:quote(103,6000)},as_of:6000}; now=6000; intervals[0](); await flush();
  assert.equal(hooks[0].tickers.BTCTRY.price,103);
  const before=calls.length; appState.currentState='background'; callbacks[0]('background'); await flush(); intervals[0](); await flush();
  assert.equal(calls.length,before+1); assert.equal(calls.at(-1),false);
  appState.currentState='active'; callbacks[0]('active'); await flush(); assert.equal(calls.at(-1),true);
  fail=true; now=40000; intervals[0](); await flush();
  assert.equal(hooks[0].state,'STALE'); assert.equal(hooks[0].tickers.BTCTRY.observed_at,6000);
  stop(); await flush(); assert.equal(intervals.length,0); assert.equal(calls.at(-1),false);
  console.log('Live prices passed: independent native polls, active lifecycle, no score/candle fabrication, ordering, crossing, stale age and outage retention.');
})().catch(error => { console.error(error); process.exitCode=1; });
