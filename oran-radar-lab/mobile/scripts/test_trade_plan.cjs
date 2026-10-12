const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');

function load(file, mocks, extras = {}) {
  const module = { exports: {} };
  const source = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true, jsx: ts.JsxEmit.React }
  }).outputText;
  vm.runInNewContext(source, { module, exports: module.exports, ...extras,
    require: name => { if (name in mocks) return mocks[name]; throw Error(name); } });
  return module.exports;
}
let hookIndex = 0, hooks = [];
const React = {
  createElement: (type, props, ...children) => ({ type, props: props || {}, children }),
  useEffect: () => {},
  useRef: initial => { const index = hookIndex++; if (!(index in hooks)) hooks[index] = { current: initial }; return hooks[index]; },
  useState: initial => {
    const index = hookIndex++;
    if (!(index in hooks)) hooks[index] = typeof initial === 'function' ? initial() : initial;
    return [hooks[index], value => { hooks[index] = typeof value === 'function' ? value(hooks[index]) : value; }];
  },
};
const fixedDate = class extends Date { static now() { return 1000; } };
const native = Object.fromEntries(['Text','TextInput','View','TouchableOpacity','Modal','ScrollView'].map(key => [key, key]));
const Metric = () => null;
const app = { styles: {}, money: n => n == null ? '—' : String(n), clock: n => String(n), pct: n => String(n), Metric };
const draft = load('src/lib/paperDraft.ts', {}, { Date: fixedDate });
const entryStats = { n: 25, effective_n: 20, filled: 15, fill_rate: .6, targets: 7, stops: 5, ambiguous: 3, net: { mean: .004, interval: [-.001,.009] }, gain: { mean: .002, interval: [-.002,.006] } };
const entryReport = { policy_id: 'pct:0.02', status: 'UNDERPOWERED', round: 1, trained_at: 900, required_samples: 600, effective_samples: 20, training_samples: 75, validation_samples: 25, evaluation_samples: 25, test: entryStats, warning: 'Sanal limit alışlar', candidates: [{ policy_id: 'pct:0.02', validation: entryStats, test: entryStats }, { policy_id: 'support:24', validation: entryStats, test: entryStats }] };
const plan = { entry_policy_id: 'pct:0.02', entry_research: entryReport, anchor_price: 100, closed_at: 900, quote_distance_fraction: .02, pair: 'SOLTRY', status: 'READY', as_of: 900, expires_at: 2000, entry: 98, target: 104, stop: 95 };
assert.equal(draft.usablePlan(plan, 'SOLTRY', 1000), true);
assert.equal(draft.usablePlan(plan, 'BTCTRY', 1000), false);
assert.equal(draft.usablePlan(plan, 'SOLTRY', 3000), false);
assert.equal(draft.usablePlan({ ...plan, stop: 99 }, 'SOLTRY', 1000), false);
assert.equal(draft.usablePlan({ ...plan, status: 'STALE' }, 'SOLTRY', 1000), false);
assert.equal(draft.paperDraft('SOLTRY', plan, 1000).stop, '95');
assert.equal(draft.paperDraft('SOLTRY', null, 1000).entry, '');
assert.equal(draft.paperDraft('SOLTRY', plan, 3000).suggested, false);

assert.equal(draft.paperDraft('SOLTRY', null, 1000).target, '');
assert.equal(draft.marketPlan(plan, 'SOLTRY', 97).status, 'STALE');
assert.equal(draft.marketPlan(plan, 'SOLTRY', null).status, 'STALE');
assert.equal(draft.marketPlan(plan, 'BTCTRY', 100), null);
assert.equal(draft.paperDraft('SOLTRY', plan, 3000).entry, '');
assert.equal(draft.paperDraft('SOLTRY', plan, 1000, false).stop, '');
let saved = [];
let marketResponse = null;
let pendingResponse = null;
const api = { serverJSON: async () => pendingResponse || marketResponse };
const flush = () => new Promise(setImmediate);
const controls = load('src/components/PaperControls.tsx', {
  react: React, 'react-native': native, '../../App': app, '../lib/paperDraft': draft,
  '../lib/paperTracker': { addPaperPosition: async (...args) => saved.push(args) }, '../lib/api': api,
}, { Date: fixedDate });
const research = load('src/components/ResearchPanel.tsx', {
  react: React, 'react-native': native, '../../App': app,
  '../lib/api': {}, '../lib/livePrices': { priceAge: () => 'Son fiyat' }, './PaperControls': controls, '../lib/paperDraft': draft,
}, { Date: fixedDate });
function walk(node, visit) {
  if (Array.isArray(node)) return node.forEach(child => walk(child, visit));
  if (!node || typeof node !== 'object') return;
  visit(node); walk(node.children, visit);
}
function collect(node, match) { const result = []; walk(node, item => { if (match(item)) result.push(item); }); return result; }
function text(node) { return Array.isArray(node) ? node.map(text).join('') : typeof node === 'object' && node ? text(node.children || []) : String(node ?? ''); }
function render(component, props) { hookIndex = 0; return component(props); }

(async () => {
  for (const pair of ['SOLTRY', 'ETHTRY']) {
    hooks = [];
    const item = { pair, display: pair, source: 'Binance TR', price: 100, observed_at: 1000, as_of: 1000,
      rally_score: 82, rally: { status: 'CURRENT', as_of: 1000, analysis: { base_score: 75, research_adjustment: 7, research_overlay: { status: 'ACTIVE', maximum_points: 20, warning: 'Bir endeks', families: [{ family: 'direction', status: 'MATURE', reason: 'HISTORICAL_AND_FORWARD_SUPPORT', points: 7, streams: [{ horizon_hours: 1, direction: 'up', status: 'MATURE', reason: 'HISTORICAL_AND_FORWARD_SUPPORT', effective_samples: 120, required_samples: 97 }] }] } } }, trade_plan: { ...plan, pair },
      research: {}, forecasts: [], hypotheses: [], hypothesis_count: 0, interpretation: [], monitor: {} };
    marketResponse = { research: { pair, price: 100, observed_at: 1000, trade_plan: { ...plan, pair } } };
    const card = render(research.ResearchCard, { item, offline: false, server: 'phone://local' });
    assert.deepEqual(collect(card, n => n.type === Metric).map(n => n.props.label), ['Önerilen alış','Önerilen hedef','Önerilen stop-loss']);
    assert.match(text(card), /Temel puan 75 · araştırma etkisi .7.0 puan/);
    assert.match(text(card), /Olgun araştırma puana katılıyor/);
    assert.match(text(card), /Alış seviyesi araştırması/);
    assert.match(text(card), /2 alış adayı/);
    assert.match(text(card), /etkin 20 . gereken 600/);
    assert.match(text(card), /Kanıt için veri toplanıyor/);
    const button = collect(card, n => n.type === controls.PaperAdd)[0];
    assert.equal(button.props.initialPair, pair);
    assert.equal(button.props.fixedPair, true);
    assert.equal(button.props.server, 'phone://local');
    hooks = [];
    let form = render(controls.PaperAdd, button.props);
    collect(form, n => n.type === 'TouchableOpacity' && String(n.props.accessibilityLabel).includes('aldım gibi'))[0].props.onPress();
    form = render(controls.PaperAdd, button.props);
    assert.equal(collect(form, n => n.props.accessibilityLabel === 'Coin ara').length, 0);
    assert.equal(collect(form, n => n.props.accessibilityLabel === 'Sanal alış fiyatı')[0].props.value, '98');
    assert.equal(collect(form, n => n.props.accessibilityLabel === 'Hedef satış fiyatı')[0].props.value, '104');
    assert.equal(collect(form, n => n.props.accessibilityLabel === 'İsteğe bağlı stop')[0].props.value, '95');
    collect(form, n => n.props.accessibilityLabel === 'Sanal alış fiyatı')[0].props.onChangeText('97');
    collect(form, n => n.props.accessibilityLabel === 'Hedef satış fiyatı')[0].props.onChangeText('103');
    collect(form, n => n.props.accessibilityLabel === 'İsteğe bağlı stop')[0].props.onChangeText('94,5');
    form = render(controls.PaperAdd, button.props);
    await collect(form, n => n.type === 'TouchableOpacity' && text(n.children) === 'Sanal alışı kaydet')[0].props.onPress();
    assert.equal(saved.at(-1)[0], pair);
    assert.equal(saved.at(-1)[4], 'phone://local');
    assert.equal(saved.at(-1)[1], 97);
    assert.equal(saved.at(-1)[2], 103);
    assert.equal(saved.at(-1)[3], 94.5);
    hooks = [];
    let details = render(research.ResearchCard, { item, offline: false });
    collect(details, n => n.props.accessibilityLabel === pair + ' analizini aç')[0].props.onPress();
    details = render(research.ResearchCard, { item, offline: false });
    assert.match(text(details), /ARAŞTIRMANIN PUANA KATKISI/);
    assert.match(text(details), /Yön modeli · .7.0 puan/);
    assert.match(text(details), /1s yükseliş · etkin 120 . 97/);
    assert.match(text(details), /ALIŞ ADAYLARININ KARŞILAŞTIRMASI/);
    assert.match(text(details), /Son 24 saat desteği/);
    assert.match(text(details), /Test hedef 7 · stop 5 · belirsiz 3/);
    const immature = render(research.ResearchCard, { item: { ...item, rally: { ...item.rally, analysis: { ...item.rally.analysis, research_adjustment: 0, research_overlay: { ...item.rally.analysis.research_overlay, status: 'WAITING' } } } }, offline: false });
    assert.match(text(immature), /Araştırma olgunlaşıyor · temel puan korunuyor/);
    const paused = render(research.ResearchCard, { item: { ...item, rally: { ...item.rally, analysis: { ...item.rally.analysis, research_adjustment: 0, research_overlay: { ...item.rally.analysis.research_overlay, status: 'PAUSED' } } } }, offline: false });
    assert.match(text(paused), /Araştırma katkısı duraklatıldı/);
    const stale = render(research.ResearchCard, { item: { ...item, trade_plan: { ...item.trade_plan, status: 'STALE', reason: 'Fiyat alış seviyesine ulaştı' } }, offline: false });
    assert.match(text(stale), /Fiyat alış seviyesine ulaştı/);
    assert.equal(collect(stale, n => n.type === controls.PaperAdd)[0].props.initialPlan.status, 'STALE');
  }

  hooks = [];
  const market = { pair: 'SOLTRY', price: 100, observed_at: 1000 };
  const props = { markets: [market], server: 'phone://local', initialPair: 'SOLTRY', fixedPair: true };
  marketResponse = { research: { ...market, trade_plan: plan } };
  let form = render(controls.PaperAdd, props);
  collect(form, n => n.props.accessibilityLabel?.includes('aldım gibi'))[0].props.onPress();
  form = render(controls.PaperAdd, props);
  assert.equal(collect(form, n => n.props.accessibilityLabel === 'Sanal alış fiyatı')[0].props.value, '');
  await flush();
  form = render(controls.PaperAdd, props);
  assert.equal(collect(form, n => n.props.accessibilityLabel === 'Sanal alış fiyatı')[0].props.value, '98');
  assert.equal(collect(form, n => n.props.accessibilityLabel === 'İsteğe bağlı stop')[0].props.value, '95');
  assert.match(text(form), /Önerilen stop-loss ₺95/);

  hooks = []; marketResponse = null;
  const staleProps = { ...props, server: '', initialPlan: { ...plan, status: 'STALE', reason: 'Yeni kapalı mum bekleniyor' } };
  form = render(controls.PaperAdd, staleProps);
  collect(form, n => n.props.accessibilityLabel?.includes('aldım gibi'))[0].props.onPress();
  form = render(controls.PaperAdd, staleProps);
  assert.equal(collect(form, n => n.props.accessibilityLabel === 'Sanal alış fiyatı')[0].props.value, '');
  assert.match(text(form), /Önerilen stop-loss ₺95/);
  assert.match(text(form), /Yeni kapalı mum bekleniyor/);
  collect(form, n => n.props.accessibilityLabel === 'Son analiz seviyelerini manuel taslağa al')[0].props.onPress();
  form = render(controls.PaperAdd, staleProps);
  assert.equal(collect(form, n => n.props.accessibilityLabel === 'Sanal alış fiyatı')[0].props.value, '98');
  assert.equal(collect(form, n => n.props.accessibilityLabel === 'İsteğe bağlı stop')[0].props.value, '95');

  hooks = [];
  let resolve;
  pendingResponse = new Promise(r => { resolve = r; });
  form = render(controls.PaperAdd, props);
  collect(form, n => n.props.accessibilityLabel?.includes('aldım gibi'))[0].props.onPress();
  form = render(controls.PaperAdd, props);
  collect(form, n => n.props.accessibilityLabel === 'Sanal alış fiyatı')[0].props.onChangeText('97');
  collect(form, n => n.props.accessibilityLabel === 'Hedef satış fiyatı')[0].props.onChangeText('103');
  collect(form, n => n.props.accessibilityLabel === 'İsteğe bağlı stop')[0].props.onChangeText('');
  resolve({ research: { ...market, trade_plan: plan } });
  await flush();
  form = render(controls.PaperAdd, props);
  assert.equal(collect(form, n => n.props.accessibilityLabel === 'Sanal alış fiyatı')[0].props.value, '97');
  assert.equal(collect(form, n => n.props.accessibilityLabel === 'Hedef satış fiyatı')[0].props.value, '103');
  assert.equal(collect(form, n => n.props.accessibilityLabel === 'İsteğe bağlı stop')[0].props.value, '');
  assert.match(text(form), /Önerilen stop-loss ₺95/);
  pendingResponse = null;


  // A response for a prior selection cannot replace the next coin's levels.
  hooks = [];
  let resolveOld, resolveNew;
  pendingResponse = new Promise(r => { resolveOld = r; });
  const eth = { pair: 'ETHTRY', price: 200, observed_at: 1000, trade_plan: { ...plan, pair: 'ETHTRY', entry: 196, target: 208, stop: 190 } };
  const chooser = { ...props, markets: [market, eth], fixedPair: false };
  form = render(controls.PaperAdd, chooser);
  collect(form, n => n.props.accessibilityLabel?.includes('aldım gibi'))[0].props.onPress();
  form = render(controls.PaperAdd, chooser);
  collect(form, n => n.props.accessibilityLabel === 'Coin ara')[0].props.onChangeText('ETH');
  pendingResponse = new Promise(r => { resolveNew = r; });
  form = render(controls.PaperAdd, chooser);
  collect(form, n => n.type === 'TouchableOpacity' && text(n.children).includes('ETHTRY · ₺200'))[0].props.onPress();
  resolveOld({ research: { ...market, trade_plan: plan } });
  await flush();
  form = render(controls.PaperAdd, chooser);
  assert.equal(collect(form, n => n.props.accessibilityLabel === 'Sanal alış fiyatı')[0].props.value, '196');
  assert.equal(collect(form, n => n.props.accessibilityLabel === 'İsteğe bağlı stop')[0].props.value, '190');
  resolveNew({ research: eth }); await flush();
  pendingResponse = null;

  // A failed refresh keeps visible last levels and never substitutes the quote.
  hooks = [];
  let reject;
  pendingResponse = new Promise((r, j) => { reject = j; });
  const cached = { ...props, initialPlan: plan };
  form = render(controls.PaperAdd, cached);
  collect(form, n => n.props.accessibilityLabel?.includes('aldım gibi'))[0].props.onPress();
  reject(Error('Ağ kapalı')); await flush();
  form = render(controls.PaperAdd, cached);
  assert.match(text(form), /Öneri güncellenemedi/);
  assert.match(text(form), /Önerilen stop-loss ₺95/);
  assert.equal(collect(form, n => n.props.accessibilityLabel === 'Sanal alış fiyatı')[0].props.value, '98');
  pendingResponse = null;

  const formatter = load('src/lib/money.ts', {});
  assert.notEqual(formatter.formatMoney(.000000048), formatter.formatMoney(.00000004704));
  assert.notEqual(formatter.formatMoney(.000041), formatter.formatMoney(.00004059));
  assert.equal(formatter.formatMoney(null), '—');
  console.log('Trade-plan UI passed: visible levels on collapsed cards, per-coin locked forms, prefilled stop, editable simulated save, freshness and pair isolation.');
})().catch(error => { console.error(error); process.exitCode = 1; });
