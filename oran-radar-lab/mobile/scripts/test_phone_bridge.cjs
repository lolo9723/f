const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');
let calls = [], response = { status: 200, body: { runtime: 'PHONE' } };
let scorePairs = null;
const native = {
  request: async (...args) => { calls.push(args); return JSON.stringify(response); },
  start: async pairs => JSON.stringify({ available: true, enabled: true, pairs }),
  stop: async () => JSON.stringify({ available: true, enabled: false }),
  selectDrive: async () => null,
  refreshScores: async pairs => { scorePairs = pairs; return JSON.stringify({ status: 'QUEUED', nextAt: 0 }); },
};
function load(file, mocks) {
  const module = { exports: {} };
  const source = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true }
  }).outputText;
  vm.runInNewContext(source, { module, exports: module.exports, JSON, Set, Error,
    require: name => { if (name in mocks) return mocks[name]; throw Error(name); } });
  return module.exports;
}
const phone = load('src/lib/phone.ts', {
  react: {}, 'react-native': { Platform: { OS: 'android' } },
  'expo-modules-core': { requireOptionalNativeModule: () => native },
});
const api = load('src/lib/api.ts', {
  './phone': phone,
  '@react-native-async-storage/async-storage': { getItem: () => { throw Error('Phone must not read cloud tokens'); } },
});
(async () => {
  const result = await api.serverJSON(phone.PHONE_API, '/v2/stop-loss/research?pair=BTCTRY&offset=100');
  assert.equal(result.runtime, 'PHONE');
  assert.deepEqual(calls[0], ['GET', '/v2/stop-loss/research', 'pair=BTCTRY&offset=100', '{}']);
  response = { status: 201, body: { position_id: 'test' } };
  await api.serverJSON(phone.PHONE_API, '/paper-positions', { method: 'POST', body: '{"stop":98}' });
  assert.deepEqual(calls[1], ['POST', '/paper-positions', '', '{"stop":98}']);
  response = { status: 409, body: { error: 'Telefon motoru duraklatılmış' } };
  await assert.rejects(api.serverJSON(phone.PHONE_API, '/v2/control/reconcile'), /duraklatılmış/);
  assert.equal((await phone.startPhone(['BTCTRY'])).enabled, true);
  assert.equal((await phone.startPhone()).pairs.length, 0);
  assert.equal((await phone.stopPhone()).enabled, false);
  assert.equal(await phone.selectDrive(), null);
  assert.equal((await phone.refreshScores(['ETHTRY'])).status, 'QUEUED');
  assert.deepEqual(scorePairs, ['ETHTRY']);
  console.log('Phone bridge passed: native routing, query/body preservation, cloud-token isolation, errors, start/stop and picker cancellation.');
})().catch(error => { console.error(error); process.exitCode = 1; });
