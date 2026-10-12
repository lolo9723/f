// Exercise the disconnected Radar screen and recovery actions without a device.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');
const React = require('react');
let states = [], calls = [], configured = 0;
let respond = async () => ({ research_backend: true, message: 'İşler kuyruğa alındı.' });
const hooks = {
  ...React,
  useEffect: () => {},
  useRef: initial => ({ current: initial }),
  useState: init => {
    const value = typeof init === 'function' ? init() : init;
    const index = states.length;
    states.push(value);
    return [value, next => { states[index] = next; }];
  },
};
const native = { Text: 'Text', View: 'View', TouchableOpacity: 'TouchableOpacity',
  FlatList: 'FlatList', ScrollView: 'ScrollView', Modal: 'Modal', StatusBar: 'StatusBar',
  ActivityIndicator: 'ActivityIndicator', TextInput: 'TextInput',
  StyleSheet: { create: x => x }, AppState: { currentState: 'active' } };
const cache = new Map();
function load(file) {
  if (cache.has(file)) return cache.get(file).exports;
  const module = { exports: {} };
  cache.set(file, module);
  const source = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: { jsx: ts.JsxEmit.React, module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2022, esModuleInterop: true },
  }).outputText;
  vm.runInNewContext(source, { module, exports: module.exports, Date, console,
    setInterval, clearInterval, require: name => {
      if (name.endsWith('/livePrices')) return { useLivePrices: () => ({ state: 'PAUSED', tickers: {} }), withLivePrice: item => item, priceAge: () => 'Son fiyat' };
      if (name === 'react') return hooks;
      if (name === 'react-native') return native;
      if (name === '../../App') return load('App.tsx');
      if (name.endsWith('/ResearchPanel')) return load('src/components/ResearchPanel.tsx');
      if (name.endsWith('/api')) return { serverJSON: async (...args) => {
        calls.push(args); return respond(...args);
      } };
      if (name.endsWith('/phone')) return { usePhone: () => ({ available: false, enabled: false }) };
      if (name.endsWith('/PhonePanel')) return { PhonePanel: 'PhonePanel' };
      if (name.endsWith('/deviceRadar')) return { useDeviceRadar: () => ({ opportunities: [], markets: [] }) };
      if (name.endsWith('/paperTracker')) return { usePaperTracker: () => ({ positions: [] }) };
      if (name.endsWith('/PaperControls')) return { PaperAdd: 'PaperAdd', PaperCard: 'PaperCard' };
      if (name === 'expo-notifications') return { setNotificationHandler: () => {} };
      if (name === 'expo-router') return { useRouter: () => ({}) };
      if (name === 'react-native-safe-area-context') return { SafeAreaView: 'SafeAreaView' };
      return {};
    } }, { filename: file });
  return module.exports;
}
function find(node, predicate) {
  if (!node || typeof node !== 'object') return null;
  if (predicate(node)) return node;
  const children = [node.props?.children, node.props?.ListHeaderComponent].flat(Infinity);
  for (const child of children) {
    const result = find(child, predicate);
    if (result) return result;
  }
  return null;
}
const app = load('App.tsx');
const { BackendControl } = load('src/components/ResearchPanel.tsx');
states = [];
const radar = app.default({ tab: 'Radar' });
const mounted = find(radar, node => node.type === BackendControl);
assert.ok(mounted, 'Default disconnected Radar must mount the control');
assert.equal(mounted.props.base, '');
function render(base) {
  states = []; calls = []; configured = 0;
  const node = BackendControl({ snapshot: null, base, offline: true,
    onConfigure: () => { configured++; } });
  const button = find(node, x => x.props?.accessibilityLabel === 'Tüm işlemleri kontrol et ve eksikleri uyandır');
  assert.ok(button, 'Recovery button remains visible before health or snapshot is received');
  assert.equal(button.props.disabled, false);
  return button;
}
(async () => {
  await render('').props.onPress();
  assert.equal(configured, 1);
  assert.equal(calls.length, 0, 'No relative-URL recovery request without a backend');
  assert.match(states[1], /Backend bağlı değil/);
  respond = async () => ({ research_backend: false });
  await render('https://legacy.example').props.onPress();
  assert.equal(calls.length, 1);
  assert.equal(calls[0][1], '/health');
  assert.match(states[1], /desteklemiyor/);
  assert.equal(states[0], false);
  respond = async (_base, path) => path === '/health'
    ? { research_backend: true } : { message: 'İşler kuyruğa alındı.' };
  await render('https://research.example').props.onPress();
  assert.equal(calls.length, 2);
  assert.equal(calls[1][1], '/v2/control/reconcile');
  assert.equal(calls[1][2].method, 'POST');
  assert.match(states[1], /kuyruğa/);
  respond = async () => { throw Error('Bağlantı kurulamadı'); };
  await render('https://offline.example').props.onPress();
  assert.equal(calls.length, 1);
  assert.match(states[1], /Bağlantı kurulamadı/);
  assert.equal(states[0], false);
  console.log('Backend controls passed: disconnected Radar visibility, settings fallback, legacy refusal, recovery enqueue, network failure.');
})().catch(error => { console.error(error); process.exitCode = 1; });
