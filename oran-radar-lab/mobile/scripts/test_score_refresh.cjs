const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');
let hooks = [], index = 0, effect, tick, appListener, cleanup, cleared = 0, removed = 0;
let calls = [], reply = 'QUEUED', failure = false;
const react = {
  createElement: (type, props, ...children) => ({ type, props: props || {}, children }),
  useState: initial => { const n = index++; if (!(n in hooks)) hooks[n] = initial; return [hooks[n], value => { hooks[n] = value; }]; },
  useEffect: callback => { effect = callback; },
};
const AppState = { currentState: 'active', addEventListener: (_, callback) => { appListener = callback; return { remove: () => removed++ }; } };
const mocks = {
  react, 'react-native': { AppState, Text: 'Text', TouchableOpacity: 'TouchableOpacity', View: 'View' },
  '../../App': { clock: value => String(value), styles: {} },
  '../lib/phone': { refreshScores: async pairs => { calls.push([...pairs]); if (failure) throw Error('network failure'); return { status: reply, nextAt: 1000 }; } },
};
const moduleOut = { exports: {} };
const js = ts.transpileModule(fs.readFileSync('src/components/ScoreRefreshPanel.tsx', 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true, jsx: ts.JsxEmit.React }
}).outputText;
vm.runInNewContext(js, { module: moduleOut, exports: moduleOut.exports,
  setInterval: (callback, milliseconds) => { assert.equal(milliseconds, 15000); tick = callback; return 1; },
  clearInterval: () => cleared++, require: name => { if (name in mocks) return mocks[name]; throw Error(name); } });
const component = moduleOut.exports.ScoreRefreshPanel;
const pairs = { current: ['BTCTRY'] };
const props = { pairs, status: { enabled: true, lastScoreCycle: 800, scoreRunning: false } };
function render() { index = 0; return component(props); }
function flatten(node) {
  if (Array.isArray(node)) return node.flatMap(flatten);
  if (!node || typeof node !== 'object') return [];
  return [node, ...flatten(node.children)];
}
function text(node) { return Array.isArray(node) ? node.map(text).join('') : node && typeof node === 'object' ? text(node.children) : String(node ?? ''); }
function button(tree) { return flatten(tree).find(n => n.props.accessibilityLabel === 'Puanları şimdi yenile'); }
(async () => {
  let tree = render(); cleanup = effect();
  assert.deepEqual(calls, [['BTCTRY']]);
  pairs.current = ['ETHTRY']; tick();
  assert.deepEqual(calls.at(-1), ['ETHTRY']);
  AppState.currentState = 'background'; tick();
  assert.equal(calls.length, 2);
  appListener('background'); assert.deepEqual(calls.at(-1), []);
  AppState.currentState = 'active'; appListener('active'); assert.deepEqual(calls.at(-1), ['ETHTRY']);
  for (const [status, expected] of [['QUEUED','kuyruğa alındı'],['RUNNING','tarama'],['THROTTLED','bekleniyor'],['PAUSED','başlat']]) {
    reply = status; tree = render(); await button(tree).props.onPress(); tree = render();
    assert.ok(text(tree).toLowerCase().includes(expected));
    assert.ok(!text(tree).includes('Puanlar güncellendi'));
  }
  failure = true; await button(tree).props.onPress(); assert.ok(text(render()).includes('network failure'));
  cleanup(); assert.equal(cleared, 1); assert.equal(removed, 1); assert.deepEqual(calls.at(-1), []);
  props.status.enabled = false; render(); const pausedCalls = calls.length; assert.equal(effect(), undefined); assert.equal(calls.length, pausedCalls);
  console.log('Score refresh UI passed: visible pairs, active-only polling, resume, paused motor, queued/busy/throttled feedback and errors.');
})().catch(error => { console.error(error); process.exitCode = 1; });
